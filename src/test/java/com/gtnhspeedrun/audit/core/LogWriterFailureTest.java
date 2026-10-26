package com.gtnhspeedrun.audit.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

/** A bad event or a cut last line never stops the chain: the writer continues, and the next start resumes the chain. */
class LogWriterFailureTest {

    private static final long DRAIN_TIMEOUT_MS = 10_000;
    private static final Logger LOG = LogManager.getLogger("LogWriterFailureTest");

    @Test
    void eventWhoseLineCantBeBuiltBecomesAnUnserializableLineAndTheChainContinues(@TempDir Path dir)
        throws IOException {
        final LogWriter writer = startWriter(dir);
        try {
            final JsonObject bad = new JsonObject();
            bad.add("value", new JsonPrimitive(new UnprintableNumber()));
            writer.submit(new PendingEvent("test_bad", bad, new TickClock.Reading(0, 1, 0), "test"));
            writer.submit(new PendingEvent("test_good", new JsonObject(), new TickClock.Reading(0, 2, 0), "test"));
            assertTrue(writer.drain(DRAIN_TIMEOUT_MS));
            assertFalse(writer.isDead());
        } finally {
            writer.close(DRAIN_TIMEOUT_MS);
        }
        final List<String> lines = Files.readAllLines(dir.resolve("audit-000001.jsonl"), StandardCharsets.UTF_8);
        final JsonObject first = parse(lines.get(0));
        final JsonObject second = parse(lines.get(1));

        assertEquals(2, lines.size());
        assertEquals(
            "unserializable_event",
            first.get("t")
                .getAsString());
        assertEquals(
            "test_bad",
            first.getAsJsonObject("data")
                .get("type")
                .getAsString());
        assertEquals(
            "test_good",
            second.get("t")
                .getAsString());
        assertEquals(
            JsonUtil.sha256Hex(lines.get(0)),
            second.get("prev")
                .getAsString());
    }

    @Test
    void cutLastLineResumesTheChainFromTheLineBefore(@TempDir Path dir) throws IOException {
        final Path logFile = writeTwoLines(dir);
        final List<String> complete = Files.readAllLines(logFile, StandardCharsets.UTF_8);
        Files.write(logFile, "{\"v\":1,\"seq\":2,\"si".getBytes(StandardCharsets.UTF_8), StandardOpenOption.APPEND);

        final ChainTail tail = ChainTail.read(dir, LOG);

        assertEquals(1, tail.seq());
        assertEquals(JsonUtil.sha256Hex(complete.get(1)), tail.hash());
        assertEquals("audit-000001.jsonl", tail.cutFile());
    }

    @Test
    void completeLastLineIsTheTailWithNoCut(@TempDir Path dir) throws IOException {
        final Path logFile = writeTwoLines(dir);
        final List<String> complete = Files.readAllLines(logFile, StandardCharsets.UTF_8);

        final ChainTail tail = ChainTail.read(dir, LOG);

        assertEquals(1, tail.seq());
        assertEquals(JsonUtil.sha256Hex(complete.get(1)), tail.hash());
        assertNull(tail.cutFile());
    }

    private static Path writeTwoLines(Path dir) throws IOException {
        final LogWriter writer = startWriter(dir);
        try {
            writer.submit(new PendingEvent("test_a", new JsonObject(), new TickClock.Reading(0, 1, 0), "test"));
            writer.submit(new PendingEvent("test_b", new JsonObject(), new TickClock.Reading(0, 2, 0), "test"));
            assertTrue(writer.drain(DRAIN_TIMEOUT_MS));
        } finally {
            writer.close(DRAIN_TIMEOUT_MS);
        }
        return dir.resolve("audit-000001.jsonl");
    }

    private static LogWriter startWriter(Path dir) throws IOException {
        final LogWriter writer = new LogWriter(
            LOG,
            new LogWriter.LogTarget("test-session", 1, dir.resolve("audit-000001.jsonl"), dir),
            new LogWriter.ChainStart(0, "genesis"));
        writer.start();
        return writer;
    }

    private static JsonObject parse(String line) {
        return new JsonParser().parse(line)
            .getAsJsonObject();
    }

    /** A number that Gson can't write: JsonWriter.value(Number) calls toString(). */
    private static final class UnprintableNumber extends Number {

        @Override
        public int intValue() {
            return 0;
        }

        @Override
        public long longValue() {
            return 0;
        }

        @Override
        public float floatValue() {
            return 0;
        }

        @Override
        public double doubleValue() {
            return 0;
        }

        @Override
        public String toString() {
            throw new IllegalStateException("unprintable");
        }
    }
}
