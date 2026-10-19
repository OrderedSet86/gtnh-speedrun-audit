package com.gtnhspeedrun.audit.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;

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
}
