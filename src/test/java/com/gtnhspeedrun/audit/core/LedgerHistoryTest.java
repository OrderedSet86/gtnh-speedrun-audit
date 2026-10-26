package com.gtnhspeedrun.audit.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.JsonObject;

/** Test that the history is the events of the chosen types that the world still has, in log order. */
class LedgerHistoryTest {

    private static final String WANTED = "dim_first_visit";
    private static final Set<String> WANTED_TYPES = Set.of(WANTED);
    private static final long NO_REWIND = Long.MAX_VALUE;
    private static final List<String> CHAIN_FILES = List
        .of("audit-000001.jsonl", "audit-000001-p2.jsonl", "audit-000002.jsonl", "audit-000002-r2.jsonl");

    @Test
    void eventsComeBackInSeqOrderWhateverFileHoldsThem(@TempDir Path logDir) throws IOException {
        final int CASES = 200;
        final Random random = new Random(7);
        for (int run = 0; run < CASES; run++) {
            final Path runDir = Files.createDirectory(logDir.resolve("run" + run));
            final int lineCount = run == 0 ? 0 : 1 + random.nextInt(40);
            final Map<String, List<String>> linesByFile = new HashMap<>();
            final List<Long> wantedSeqs = new ArrayList<>();
            for (long seq = 0; seq < lineCount; seq++) {
                final String type = random.nextBoolean() ? WANTED : "block_placed";
                if (WANTED.equals(type)) {
                    wantedSeqs.add(seq);
                }
                linesByFile
                    .computeIfAbsent(CHAIN_FILES.get(random.nextInt(CHAIN_FILES.size())), name -> new ArrayList<>())
                    .add(formatLine(seq, type, seq * 20, new JsonObject()));
            }
            for (Map.Entry<String, List<String>> file : linesByFile.entrySet()) {
                Files.write(runDir.resolve(file.getKey()), file.getValue(), StandardCharsets.UTF_8);
            }

            final LedgerHistory.History history = LedgerHistory.read(runDir, WANTED_TYPES, NO_REWIND);

            assertEquals(wantedSeqs, readSeqs(history), "run " + run);
            for (LedgerHistory.Event event : history.events()) {
                assertEquals(event.seq() * 20, event.igtTicks());
            }
        }
    }

    @Test
    void earlierRollbackDropsExactlyTheRewoundEventsAndKeepsTheReachAfterIt(@TempDir Path logDir) throws IOException {
        final long SESSION_START_SEQ = 11;
        final long TAIL_SEQ = SESSION_START_SEQ - 1;
        for (long eventsRewound = 0; eventsRewound <= TAIL_SEQ; eventsRewound++) {
            final List<String> lines = new ArrayList<>();
            for (long seq = 0; seq <= TAIL_SEQ; seq++) {
                lines.add(formatLine(seq, WANTED, seq, new JsonObject()));
            }
            lines.add(formatLine(SESSION_START_SEQ, "session_start", 0, formatSessionStart(TAIL_SEQ, eventsRewound)));
            lines.add(formatLine(SESSION_START_SEQ + 1, WANTED, 0, new JsonObject()));
            final Path runDir = Files.createDirectory(logDir.resolve("rewound" + eventsRewound));
            Files.write(runDir.resolve("audit-000002.jsonl"), lines, StandardCharsets.UTF_8);

            final List<Long> expected = new ArrayList<>();
            for (long seq = 0; seq <= TAIL_SEQ - eventsRewound; seq++) {
                expected.add(seq);
            }
            expected.add(SESSION_START_SEQ + 1);
            assertEquals(expected, readSeqs(LedgerHistory.read(runDir, WANTED_TYPES, NO_REWIND)));
        }
    }

    @Test
    void sessionStartWithNoRollbackDropsNothing(@TempDir Path logDir) throws IOException {
        final JsonObject cleanStart = new JsonObject();
        cleanStart.add("logTail", formatSessionStart(1, 0).getAsJsonObject("logTail"));
        Files.write(
            logDir.resolve("audit-000001.jsonl"),
            List.of(
                formatLine(0, WANTED, 0, new JsonObject()),
                formatLine(1, WANTED, 5, new JsonObject()),
                formatLine(2, "session_start", 5, cleanStart),
                formatLine(3, WANTED, 9, new JsonObject())),
            StandardCharsets.UTF_8);

        assertEquals(List.of(0L, 1L, 3L), readSeqs(LedgerHistory.read(logDir, WANTED_TYPES, 3)));
    }

    @Test
    void lastWorldSeqOfThisStartDropsTheTail(@TempDir Path logDir) throws IOException {
        final long LAST_SEQ = 6;
        final List<String> lines = new ArrayList<>();
        for (long seq = 0; seq <= LAST_SEQ; seq++) {
            lines.add(formatLine(seq, WANTED, seq, new JsonObject()));
        }
        Files.write(logDir.resolve("audit-000001.jsonl"), lines, StandardCharsets.UTF_8);

        for (long lastWorldSeq = -1; lastWorldSeq <= LAST_SEQ; lastWorldSeq++) {
            final List<Long> seqs = readSeqs(LedgerHistory.read(logDir, WANTED_TYPES, lastWorldSeq));
            assertEquals(lastWorldSeq + 1, seqs.size());
            for (long seq : seqs) {
                assertTrue(seq <= lastWorldSeq);
            }
        }
    }

    @Test
    void cutLineIsSkippedAndCounted(@TempDir Path logDir) throws IOException {
        final String fullLine = formatLine(1, WANTED, 3, new JsonObject());
        Files.write(
            logDir.resolve("audit-000001.jsonl"),
            List.of(formatLine(0, WANTED, 0, new JsonObject()), fullLine.substring(0, fullLine.length() / 2)),
            StandardCharsets.UTF_8);

        final LedgerHistory.History history = LedgerHistory.read(logDir, WANTED_TYPES, NO_REWIND);

        assertEquals(List.of(0L), readSeqs(history));
        assertEquals(1, history.skippedLines());
    }

    @Test
    void fileThatIsNotAChainFileIsNotRead(@TempDir Path logDir) throws IOException {
        Files.write(
            logDir.resolve("notes.jsonl"),
            List.of(formatLine(0, WANTED, 0, new JsonObject())),
            StandardCharsets.UTF_8);

        final LedgerHistory.History history = LedgerHistory.read(logDir, WANTED_TYPES, NO_REWIND);

        assertEquals(List.of(), history.events());
        assertEquals(0, history.fileCount());
    }

    @Test
    void missingLogDirectoryGivesNoEvents(@TempDir Path dir) throws IOException {
        assertEquals(
            List.of(),
            LedgerHistory.read(dir.resolve("log"), WANTED_TYPES, NO_REWIND)
                .events());
    }

    @Test
    void eventDataComesBackAsLogged(@TempDir Path logDir) throws IOException {
        final JsonObject data = new JsonObject();
        data.addProperty("dim", -1);
        data.addProperty("name", "a \"quoted\" \\t\":\"name");
        Files.write(
            logDir.resolve("audit-000001.jsonl"),
            List.of(formatLine(0, WANTED, 42, data)),
            StandardCharsets.UTF_8);

        final LedgerHistory.Event event = LedgerHistory.read(logDir, WANTED_TYPES, NO_REWIND)
            .events()
            .get(0);

        assertEquals(WANTED, event.type());
        assertEquals(data, event.data());
        assertEquals(42, event.igtTicks());
    }

    @Test
    void lineWhoseTypeIsAnObjectIsSkippedAndCountedAndTheRestReplay(@TempDir Path logDir) throws IOException {
        final JsonObject nested = new JsonObject();
        nested.addProperty("t", WANTED);
        final String goodBefore = formatLine(0, WANTED, 0, new JsonObject());
        // a line whose top-level t is an object, with a nested t that matches the prefilter
        final String objectType = "{\"seq\":1,\"ticks\":0,\"t\":{\"x\":1},\"data\":" + JsonUtil.GSON.toJson(nested)
            + "}";
        final String goodAfter = formatLine(2, WANTED, 0, new JsonObject());
        Files.write(
            logDir.resolve("audit-000001.jsonl"),
            List.of(goodBefore, objectType, goodAfter),
            StandardCharsets.UTF_8);

        final LedgerHistory.History history = LedgerHistory.read(logDir, WANTED_TYPES, NO_REWIND);

        assertEquals(List.of(0L, 2L), readSeqs(history));
        assertEquals(1, history.skippedLines());
    }

    @Test
    void bytesThatAreNotUtf8CostNoOtherLine(@TempDir Path logDir) throws IOException {
        final JsonObject data = new JsonObject();
        data.addProperty("name", "PLACEHOLDER");
        final byte[] good = (formatLine(0, WANTED, 0, new JsonObject()) + "\n").getBytes(StandardCharsets.UTF_8);
        final byte[] withBadBytes = (formatLine(1, WANTED, 0, data) + "\n").getBytes(StandardCharsets.UTF_8);
        final byte[] after = (formatLine(2, WANTED, 0, new JsonObject()) + "\n").getBytes(StandardCharsets.UTF_8);
        final int at = new String(withBadBytes, StandardCharsets.UTF_8).indexOf("PLACEHOLDER");
        // 0xC3 0x28 is an invalid two-byte UTF-8 sequence
        withBadBytes[at] = (byte) 0xC3;
        withBadBytes[at + 1] = (byte) 0x28;
        final byte[] file = new byte[good.length + withBadBytes.length + after.length];
        System.arraycopy(good, 0, file, 0, good.length);
        System.arraycopy(withBadBytes, 0, file, good.length, withBadBytes.length);
        System.arraycopy(after, 0, file, good.length + withBadBytes.length, after.length);
        Files.write(logDir.resolve("audit-000001.jsonl"), file);

        final LedgerHistory.History history = LedgerHistory.read(logDir, WANTED_TYPES, NO_REWIND);

        assertEquals(List.of(0L, 1L, 2L), readSeqs(history));
        assertEquals(0, history.skippedLines());
    }

    private static List<Long> readSeqs(LedgerHistory.History history) {
        final List<Long> seqs = new ArrayList<>();
        for (LedgerHistory.Event event : history.events()) {
            seqs.add(event.seq());
        }
        return seqs;
    }

    // the keys of a LogWriter line, in the writer's order
    private static String formatLine(long seq, String type, long ticks, JsonObject data) {
        final JsonObject line = new JsonObject();
        line.addProperty("v", 1);
        line.addProperty("seq", seq);
        line.addProperty("sid", "test-session");
        line.addProperty("sidx", 1);
        line.addProperty("t", type);
        line.addProperty("wall", 0);
        line.addProperty("ticks", ticks);
        line.addProperty("pticks", ticks);
        line.addProperty("thread", "Server thread");
        line.add("data", data);
        line.addProperty("prev", "0");
        return JsonUtil.GSON.toJson(line);
    }

    private static JsonObject formatSessionStart(long tailSeq, long eventsRewound) {
        final JsonObject logTail = new JsonObject();
        logTail.addProperty("seq", tailSeq);
        logTail.addProperty("hash", "0");
        final JsonObject rollback = new JsonObject();
        rollback.addProperty("eventsRewound", eventsRewound);
        rollback.addProperty("wallGapMs", 0);
        final JsonObject data = new JsonObject();
        data.addProperty("verifyVerdict", "WORLD_ROLLBACK");
        data.add("logTail", logTail);
        data.add("rollback", rollback);
        return data;
    }
}
