package com.gtnhspeedrun.audit.export;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Measure the heap that {@code AuditExport.readAllLines} needs. That method parses every JSONL line of every session
 * into one list of JsonObjects, to sort the lines by seq. The Gson object model is much larger than the source bytes,
 * and a GTNH run logs a line per machine placed. The printed numbers show whether a 500-hour run can be exported on a
 * server near its heap ceiling. The export is the last step of a run, and the submission is lost on an
 * OutOfMemoryError there.
 *
 * <p>
 * The test asserts no budget, because heap measurement is too noisy for a build gate. The test asserts only that the
 * measurement worked, and prints the expansion factor on representative lines.
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

        // the list is still used here, after the measurement. Without this use, the JIT can treat the list as dead
        // and the GC can collect the list early
        assertEquals(LINES, parsed.size());
        assertTrue(heap > 0, "heap delta not measurable: " + heap);
    }

    /** Build a line shaped like a real machine_placed line, the type with the most lines in the log of a GTNH run. */
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
        final Runtime runtime = Runtime.getRuntime();
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
        return runtime.totalMemory() - runtime.freeMemory();
    }
}
