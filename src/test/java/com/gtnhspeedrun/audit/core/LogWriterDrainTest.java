package com.gtnhspeedrun.audit.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.apache.logging.log4j.LogManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.JsonObject;

/**
 * Test that {@link LogWriter#drain} returns only after the writer appended every job submitted before the call. A clean
 * stop publishes the anchor right after drain(), so an anchor one seq behind session_end reads as WORLD_ROLLBACK at the
 * next start.
 */
class LogWriterDrainTest {

    @Test
    void drainReturnsOnlyAfterEverySubmittedLineIsPublished(@TempDir Path dir) throws IOException {
        final int EVENTS = 500;
        final long DRAIN_TIMEOUT_MS = 10_000;
        final LogWriter writer = new LogWriter(
            LogManager.getLogger("LogWriterDrainTest"),
            new LogWriter.LogTarget("test-session", 1, dir.resolve("audit-000001.jsonl"), dir),
            new LogWriter.ChainStart(0, "genesis"));
        writer.start();
        try {
            // one event per drain, so the writer often takes the event before drain() first looks
            for (int seq = 0; seq < EVENTS; seq++) {
                writer.submit(new PendingEvent("test", new JsonObject(), new TickClock.Reading(0, 0, 0), "test"));
                assertTrue(writer.drain(DRAIN_TIMEOUT_MS));
                assertEquals(seq, writer.publishedSeq());
            }
        } finally {
            writer.close(DRAIN_TIMEOUT_MS);
        }
    }

    // `command` and `world_saved` are urgent: the writer forces the file once the queue is empty after them
    private static final String[] TYPES = { "command", "world_saved", "test" };

    @Test
    void drainReturnsOnlyAfterEveryUrgentAndPlainLineIsInTheFile(@TempDir Path dir) throws IOException {
        final int GENERATED_BATCHES = 200;
        final int MAX_BATCH = 64;
        final long DRAIN_TIMEOUT_MS = 10_000;
        final Random random = new Random(20261107L);
        final List<Integer> batchSizes = new ArrayList<>(List.of(1, MAX_BATCH, 1, MAX_BATCH));
        for (int batch = 0; batch < GENERATED_BATCHES; batch++) {
            batchSizes.add(1 + random.nextInt(MAX_BATCH));
        }
        final Path logFile = dir.resolve("audit-000001.jsonl");
        final LogWriter writer = new LogWriter(
            LogManager.getLogger("LogWriterDrainTest"),
            new LogWriter.LogTarget("test-session", 1, logFile, dir),
            new LogWriter.ChainStart(0, "genesis"));
        writer.start();
        try {
            int submitted = 0;
            for (int batchSize : batchSizes) {
                for (int line = 0; line < batchSize; line++) {
                    final String type = TYPES[random.nextInt(TYPES.length)];
                    writer.submit(new PendingEvent(type, new JsonObject(), new TickClock.Reading(0, 0, 0), "test"));
                }
                submitted += batchSize;
                assertTrue(writer.drain(DRAIN_TIMEOUT_MS));
                assertEquals(submitted - 1, writer.publishedSeq());
                assertEquals(
                    submitted,
                    Files.readAllLines(logFile, StandardCharsets.UTF_8)
                        .size());
            }
        } finally {
            writer.close(DRAIN_TIMEOUT_MS);
        }
    }
}
