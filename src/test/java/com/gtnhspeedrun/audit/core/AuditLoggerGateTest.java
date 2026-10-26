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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.IntUnaryOperator;

import org.apache.logging.log4j.LogManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Test that {@link AuditLogger} drops and counts every line from the thread of the logger's {@link ClientThreadGate},
 * and writes every other line.
 */
class AuditLoggerGateTest {

    private static final long DRAIN_TIMEOUT_MS = 10_000;
    private static final int THREADS = 3; // thread 0 is the gated thread
    private static final int LINES = 300;

    @Test
    void lineIsDroppedOnlyFromTheGatedThread(@TempDir Path dir) throws Exception {
        final long SEED = 20261025L;
        final Random random = new Random(SEED);
        final Outcome outcome = logFrom(dir, true, line -> random.nextInt(THREADS));

        assertEquals(outcome.gatedLines(), outcome.dropped());
        assertEquals(
            LINES - outcome.gatedLines(),
            outcome.writtenThreads()
                .size());
        assertTrue(
            outcome.writtenThreads()
                .stream()
                .noneMatch(outcome.gatedThreadName()::equals));
    }

    @Test
    void everyLineFromTheGatedThreadIsDropped(@TempDir Path dir) throws Exception {
        final Outcome outcome = logFrom(dir, true, line -> 0);

        assertEquals(LINES, outcome.dropped());
        assertEquals(
            0,
            outcome.writtenThreads()
                .size());
    }

    @Test
    void gatedThreadThatLogsNothingDropsNothing(@TempDir Path dir) throws Exception {
        final Outcome outcome = logFrom(dir, true, line -> 1 + line % (THREADS - 1));

        assertEquals(0, outcome.dropped());
        assertEquals(
            LINES,
            outcome.writtenThreads()
                .size());
    }

    @Test
    void noGateDropsNothing(@TempDir Path dir) throws Exception {
        final Outcome outcome = logFrom(dir, false, line -> line % THREADS);

        assertEquals(0, outcome.dropped());
        assertEquals(
            LINES,
            outcome.writtenThreads()
                .size());
    }

    /**
     * Log {@link #LINES} lines, line {@code i} on thread {@code threadOfLine(i)}, with thread 0 gated when
     * {@code gated} is true. The lines are logged one after another.
     */
    private static Outcome logFrom(Path dir, boolean gated, IntUnaryOperator threadOfLine)
        throws IOException, InterruptedException, ExecutionException {
        final List<ExecutorService> threads = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            threads.add(Executors.newSingleThreadExecutor());
        }
        final LogWriter writer = new LogWriter(
            LogManager.getLogger("AuditLoggerGateTest"),
            new LogWriter.LogTarget("test-session", 1, dir.resolve("audit-000001.jsonl"), dir),
            new LogWriter.ChainStart(0, "genesis"));
        writer.start();
        try {
            final Thread gatedThread = threads.get(0)
                .submit(Thread::currentThread)
                .get();
            final AuditLogger logger = new AuditLogger(
                writer,
                new TickClock(0, 0),
                gated ? new ClientThreadGate(gatedThread) : ClientThreadGate.NONE);
            int gatedLines = 0;
            for (int line = 0; line < LINES; line++) {
                final int thread = threadOfLine.applyAsInt(line);
                gatedLines += gated && thread == 0 ? 1 : 0;
                threads.get(thread)
                    .submit(() -> logger.log("test_gate", new JsonObject()))
                    .get();
            }
            assertTrue(writer.drain(DRAIN_TIMEOUT_MS));
            return new Outcome(gatedLines, logger.countDroppedLines(), readThreads(dir), gatedThread.getName());
        } finally {
            writer.close(DRAIN_TIMEOUT_MS);
            for (ExecutorService thread : threads) {
                thread.shutdownNow();
            }
        }
    }

    private static List<String> readThreads(Path dir) throws IOException {
        final Path logFile = dir.resolve("audit-000001.jsonl");
        final List<String> threadNames = new ArrayList<>();
        if (!Files.exists(logFile)) {
            return threadNames;
        }
        for (String line : Files.readAllLines(logFile, StandardCharsets.UTF_8)) {
            threadNames.add(
                new JsonParser().parse(line)
                    .getAsJsonObject()
                    .get("thread")
                    .getAsString());
        }
        return threadNames;
    }

    /** The count of gated lines, the count of dropped lines, and the thread name of each written line. */
    private record Outcome(int gatedLines, long dropped, List<String> writtenThreads, String gatedThreadName) {}
}
