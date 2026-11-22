package com.gtnhspeedrun.audit.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.apache.logging.log4j.LogManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Per identity, at most the limiter's lines per window get a line, and the report line of the window has the count of
 * every other attempt: {@link AttemptLimiter#PLAYER_LINES_PER_WINDOW} per player and type, and 1 per command block and
 * command.
 */
class AttemptLimiterTest {

    private static final Duration WINDOW = Duration.ofMillis(300);
    private static final long DRAIN_TIMEOUT_MS = 10_000;
    private static final String[] PLAYERS = { "p1", "p2", "p3" };
    private static final String[] TYPES = { "nbt_edit", "nei_packet" };

    @Test
    void generatedBurstsNeverPassTheLimitAndEveryDropIsCounted(@TempDir Path dir)
        throws IOException, InterruptedException {
        final int ROUNDS = 5;
        final int MAX_ATTEMPTS = 60;
        final Random random = new Random(1025L);
        for (int round = 0; round < ROUNDS; round++) {
            final Map<String, Integer> attempts = new HashMap<>();
            for (String player : PLAYERS) {
                for (String type : TYPES) {
                    attempts.put(player + "/" + type, random.nextInt(MAX_ATTEMPTS + 1));
                }
            }
            runRound(dir.resolve("round" + round), attempts);
        }
    }

    @Test
    void boundaryCountsTwentyAndTwentyOne(@TempDir Path dir) throws IOException, InterruptedException {
        final Map<String, Integer> attempts = new HashMap<>();
        attempts.put("p1/nbt_edit", AttemptLimiter.PLAYER_LINES_PER_WINDOW);
        attempts.put("p2/nbt_edit", AttemptLimiter.PLAYER_LINES_PER_WINDOW + 1);
        attempts.put("p1/nei_packet", 0);
        runRound(dir, attempts);
    }

    @Test
    void commandBlockRunsGetOneLinePerWindow(@TempDir Path dir) throws IOException, InterruptedException {
        final int MAX_RUNS = 40;
        final Random random = new Random(1026L);
        final Map<String, Integer> runs = new HashMap<>();
        for (String position : new String[] { "1,2,3", "4,5,6", "7,8,9", "10,11,12" }) {
            runs.put(position, random.nextInt(MAX_RUNS + 1));
        }
        runs.put("0,0,0", 0);
        runs.put("0,0,1", 1);
        runs.put("0,0,2", 2);
        final Map<String, Integer> allowed = new HashMap<>();
        runLimiter(dir, 1, "command_repeats", limiter -> {
            for (Map.Entry<String, Integer> entry : runs.entrySet()) {
                for (int run = 0; run < entry.getValue(); run++) {
                    final JsonObject identity = new JsonObject();
                    identity.addProperty("senderType", "commandblock");
                    identity.addProperty("pos", entry.getKey());
                    identity.addProperty("cmd", "cofh");
                    if (limiter.tryLog(identity)) {
                        allowed.merge(entry.getKey(), 1, Integer::sum);
                    }
                }
            }
            limiter.reportAllWindows();
        });

        final Map<String, Long> dropped = readDropped(
            dir.resolve("audit-000001.jsonl"),
            "command_repeats",
            "pos",
            "cmd");
        for (Map.Entry<String, Integer> entry : runs.entrySet()) {
            final int sent = entry.getValue();
            final int lines = allowed.getOrDefault(entry.getKey(), 0);

            assertEquals(Math.min(sent, 1), lines, entry.getKey());
            assertEquals(sent - lines, dropped.getOrDefault(entry.getKey() + "/cofh", 0L), entry.getKey());
        }
    }

    /** Send {@code attempts} per "player/type" in one window, end the window, then check the lines and the drops. */
    private static void runRound(Path dir, Map<String, Integer> attempts) throws IOException, InterruptedException {
        final Map<String, Integer> allowed = new HashMap<>();
        runLimiter(dir, AttemptLimiter.PLAYER_LINES_PER_WINDOW, "rate_limited", limiter -> {
            for (Map.Entry<String, Integer> entry : attempts.entrySet()) {
                final String[] playerAndType = entry.getKey()
                    .split("/");
                for (int attempt = 0; attempt < entry.getValue(); attempt++) {
                    if (limiter.tryLog(playerAndType[0], playerAndType[0], playerAndType[1])) {
                        allowed.merge(entry.getKey(), 1, Integer::sum);
                    }
                }
            }
            Thread.sleep(WINDOW.toMillis() + 50);
            limiter.reportEndedWindows();
        });

        final Map<String, Long> dropped = readDropped(
            dir.resolve("audit-000001.jsonl"),
            "rate_limited",
            "uuid",
            "type");
        for (Map.Entry<String, Integer> entry : attempts.entrySet()) {
            final int sent = entry.getValue();
            final int lines = allowed.getOrDefault(entry.getKey(), 0);

            assertEquals(Math.min(sent, AttemptLimiter.PLAYER_LINES_PER_WINDOW), lines, entry.getKey());
            assertEquals(sent - lines, dropped.getOrDefault(entry.getKey(), 0L), entry.getKey());
        }
    }

    /** Run {@code use} on a limiter that logs to a new log in {@code dir}, then wait for every line to be written. */
    private static void runLimiter(Path dir, int linesPerWindow, String reportType, LimiterUse use)
        throws IOException, InterruptedException {
        Files.createDirectories(dir);
        final LogWriter writer = new LogWriter(
            LogManager.getLogger("AttemptLimiterTest"),
            new LogWriter.LogTarget("test-session", 1, dir.resolve("audit-000001.jsonl"), dir),
            new LogWriter.ChainStart(0, "genesis"));
        writer.start();
        try {
            use.accept(
                new AttemptLimiter(
                    new AuditLogger(writer, new TickClock(0, 0), ClientThreadGate.NONE),
                    new AttemptLimiter.Limit(WINDOW, linesPerWindow, reportType)));
            assertTrue(writer.drain(DRAIN_TIMEOUT_MS));
        } finally {
            writer.close(DRAIN_TIMEOUT_MS);
        }
    }

    /** Map the "keyField/typeField" key of each {@code reportType} line to the line's dropped count. */
    private static Map<String, Long> readDropped(Path logFile, String reportType, String keyField, String typeField)
        throws IOException {
        final Map<String, Long> dropped = new HashMap<>();
        final List<String> lines = Files.exists(logFile) ? Files.readAllLines(logFile, StandardCharsets.UTF_8)
            : List.of();
        for (String rawLine : lines) {
            final JsonObject line = new JsonParser().parse(rawLine)
                .getAsJsonObject();
            if (!line.get("t")
                .getAsString()
                .equals(reportType)) {
                continue;
            }
            final JsonObject data = line.getAsJsonObject("data");
            dropped.merge(
                data.get(keyField)
                    .getAsString() + "/"
                    + data.get(typeField)
                        .getAsString(),
                data.get("dropped")
                    .getAsLong(),
                Long::sum);
        }
        return dropped;
    }

    @FunctionalInterface
    private interface LimiterUse {

        void accept(AttemptLimiter limiter) throws InterruptedException;
    }
}
