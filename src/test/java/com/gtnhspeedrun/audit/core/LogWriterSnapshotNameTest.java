package com.gtnhspeedrun.audit.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

/**
 * Test that every snapshot job gets a separate file. A restore replays the ticks in a file stem, so stems repeat.
 */
class LogWriterSnapshotNameTest {

    @Test
    void repeatedStemsGiveOneFilePerReferenceLine(@TempDir Path dir) throws IOException {
        final int JOBS = 120;
        final long DRAIN_TIMEOUT_MS = 10_000;
        final List<String> stems = List.of("nodes-477200", "inv-495200-ebf530fb-interval", "ae2-513200-g1");
        final Random random = new Random(27);
        final LogWriter writer = new LogWriter(
            LogManager.getLogger("LogWriterSnapshotNameTest"),
            new LogWriter.LogTarget("test-session", 1, dir.resolve("audit-000001.jsonl"), dir),
            new LogWriter.ChainStart(0, "genesis"));
        writer.start();
        try {
            for (int job = 0; job < JOBS; job++) {
                // a plain event between some jobs, so the seq of a reference line isn't the job's index
                if (random.nextBoolean()) {
                    writer.submit(new PendingEvent("test", new JsonObject(), new TickClock.Reading(0, 0, 0), "test"));
                }
                final JsonPrimitive content = new JsonPrimitive(job);
                writer.submitSnapshot(
                    new LogWriter.SnapshotJob(
                        "test_snapshot",
                        stems.get(random.nextInt(stems.size())),
                        () -> content,
                        new JsonObject(),
                        new TickClock.Reading(0, 0, 0)));
            }
            assertTrue(writer.drain(DRAIN_TIMEOUT_MS));
        } finally {
            writer.close(DRAIN_TIMEOUT_MS);
        }

        final Set<String> files = new HashSet<>();
        for (String rawLine : Files.readAllLines(dir.resolve("audit-000001.jsonl"), StandardCharsets.UTF_8)) {
            final JsonObject line = new JsonParser().parse(rawLine)
                .getAsJsonObject();
            if (!"test_snapshot".equals(
                line.get("t")
                    .getAsString())) {
                continue;
            }
            final JsonObject data = line.getAsJsonObject("data");
            final String file = data.get("file")
                .getAsString();
            assertTrue(
                file.endsWith(
                    "-" + line.get("seq")
                        .getAsLong() + ".json.gz"),
                file);
            assertEquals(
                data.get("fileSha256")
                    .getAsString(),
                JsonUtil.sha256Hex(Files.readAllBytes(dir.resolve(file))));
            files.add(file);
        }
        assertEquals(JOBS, files.size());
    }
}
