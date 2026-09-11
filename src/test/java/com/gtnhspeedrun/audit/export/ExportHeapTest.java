package com.gtnhspeedrun.audit.export;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * {@code AuditExport.readAllLines} holds the entire run — every JSONL line of every session — as parsed
 * JsonObjects in one list so it can sort by seq. Gson's object model is far larger than the bytes it came
 * from, and a GTNH run logs a line per machine placed, so the question is whether a 500-hour run can still
 * be exported on a server that is already near its heap ceiling. Exporting is the last thing a runner does;
 * OOMing there would lose the submission at the worst possible moment.
 *
 * <p>
 * This measures the real expansion factor on representative lines rather than guessing at it. It does not
 * assert a budget — heap measurement is too noisy for a build gate — it asserts only that the measurement
 * itself worked, and prints the number that the sizing decision rests on.
 */
class ExportHeapTest {

    private static final int LINES = 50_000;

    @Test
    void parsedLineFootprintIsMeasured() {
        final List<String> raw = new ArrayList<>(LINES);
        long rawBytes = 0;
        for (int i = 0; i < LINES; i++) {
            final String line = sampleLine(i);
            raw.add(line);
            rawBytes += line.getBytes(StandardCharsets.UTF_8).length;
        }

        final long before = usedHeap();
        final List<JsonObject> parsed = new ArrayList<>(LINES);
        for (String line : raw) {
            parsed.add(
                new JsonParser().parse(line)
                    .getAsJsonObject());
        }
        final long after = usedHeap();

        final long heap = after - before;
        final double perLine = (double) heap / LINES;
        final double expansion = (double) heap / rawBytes;

        System.out.printf(
            "export heap: %d lines, %.1f MB raw -> %.1f MB parsed (%.0f B/line, %.1fx expansion)%n",
            LINES,
            rawBytes / 1048576.0,
            heap / 1048576.0,
            perLine,
            expansion);
        for (int lines : new int[] { 250_000, 500_000, 1_000_000 }) {
            System.out.printf(
                "  projected at %,d lines: %.2f GB retained by readAllLines%n",
                lines,
                perLine * lines / 1073741824.0);
        }

        // Keep the list reachable past the measurement, or the JIT is free to collect it early.
        assertTrue(parsed.size() == LINES);
        assertTrue(heap > 0, "heap delta not measurable: " + heap);
    }

    /** Shaped like a real machine_placed line — the type that dominates a GTNH run's log volume. */
    private static String sampleLine(int seq) {
        return "{\"v\":1,\"seq\":" + seq
            + ",\"sid\":\"6f1c0f4e-5f1a-4a9e-9f2a-2b3c4d5e6f70\",\"sidx\":7,\"t\":\"machine_placed\","
            + "\"wall\":1789090599040,\"ticks\":"
            + (seq * 37L)
            + ",\"pticks\":"
            + (seq * 31L)
            + ",\"thread\":\"Server thread\",\"data\":{\"playerUuid\":\"61def193-eb8f-31c9-926f-fbd6c10d1486\","
            + "\"playerName\":\"Runner\",\"block\":\"gregtech:gt.blockmachines\",\"meta\":"
            + (seq % 32000)
            + ",\"dim\":0,\"x\":"
            + (seq % 5000)
            + ",\"y\":64,\"z\":"
            + (seq % 4000)
            + "},\"prev\":\"ebff0f24007eb87e9b783cfde7f2c66db4c729b272e083a875386eeeaf87a3fc\"}";
    }

    private static long usedHeap() {
        final Runtime rt = Runtime.getRuntime();
        for (int i = 0; i < 4; i++) {
            System.gc();
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread()
                    .interrupt();
                break;
            }
        }
        return rt.totalMemory() - rt.freeMemory();
    }
}
