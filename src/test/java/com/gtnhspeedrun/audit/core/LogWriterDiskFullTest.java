package com.gtnhspeedrun.audit.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.apache.logging.log4j.LogManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.JsonObject;

/**
 * A write that fails halfway, here from the file size limit of {@code ulimit -f}, leaves the log ending at a complete
 * line, so the next start resumes from that line with no cut.
 */
class LogWriterDiskFullTest {

    private static final int LIMIT_KIB = 64;
    private static final long CHILD_TIMEOUT_S = 60;

    @Test
    void failedWriteLeavesTheLogEndingAtACompleteLine(@TempDir Path dir) throws IOException, InterruptedException {
        assumeTrue(Files.isExecutable(Paths.get("/bin/bash")), "needs bash for ulimit");
        final String java = ProcessHandle.current()
            .info()
            .command()
            .orElse("java");
        // a write past the size limit fails with EFBIG ("File too large"), and the child keeps running
        final String command = "ulimit -f " + LIMIT_KIB
            + " && exec \"$0\" -cp \"$1\" com.gtnhspeedrun.audit.core.LogWriterDiskFullTest \"$2\"";
        final Process child = new ProcessBuilder(
            "/bin/bash",
            "-c",
            command,
            java,
            System.getProperty("java.class.path"),
            dir.toString()).inheritIO()
                .start();
        assertTrue(child.waitFor(CHILD_TIMEOUT_S, TimeUnit.SECONDS), "child writer timed out");
        assertEquals(0, child.exitValue());

        final Path logFile = dir.resolve("audit-000001.jsonl");
        final byte[] bytes = Files.readAllBytes(logFile);
        final List<String> lines = Files.readAllLines(logFile, StandardCharsets.UTF_8);
        final ChainTail tail = ChainTail.read(dir, LogManager.getLogger("LogWriterDiskFullTest"));

        assertTrue(bytes.length > 0 && bytes.length <= LIMIT_KIB * 1024, "file size " + bytes.length);
        assertEquals('\n', bytes[bytes.length - 1]);
        assertEquals(lines.size() - 1, tail.seq());
        assertNull(tail.cutFile());
    }

    /**
     * Write events of about 1 KiB into {@code args[0]} until the writer dies, then exit 0. Exit 1 when the writer is
     * still alive after more data than the size limit.
     */
    public static void main(String[] args) throws IOException {
        final int EVENTS = 4 * LIMIT_KIB;
        final long DRAIN_TIMEOUT_MS = 10_000;
        final Path dir = Paths.get(args[0]);
        final LogWriter writer = new LogWriter(
            LogManager.getLogger("LogWriterDiskFullTest"),
            new LogWriter.LogTarget("test-session", 1, dir.resolve("audit-000001.jsonl"), dir),
            new LogWriter.ChainStart(0, "genesis"));
        writer.start();
        final JsonObject data = new JsonObject();
        data.addProperty("padding", "x".repeat(1000));
        for (int event = 0; event < EVENTS && !writer.isDead(); event++) {
            writer.submit(new PendingEvent("test_fill", data, new TickClock.Reading(0, event, 0), "test"));
            writer.drain(DRAIN_TIMEOUT_MS);
        }
        System.exit(writer.isDead() ? 0 : 1);
    }
}
