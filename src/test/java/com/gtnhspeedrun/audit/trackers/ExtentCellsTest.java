package com.gtnhspeedrun.audit.trackers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

import org.junit.jupiter.api.Test;

/**
 * Test that the cells of {@link ExtentCells#encodeExtents} decode, by the layout in the class javadoc, to the last key
 * given for each position.
 */
class ExtentCellsTest {

    private static final long SEED = 20261106L;
    private static final int GENERATED_CASES = 300;

    @Test
    void generatedPositionsDecodeToTheirLastKey() throws DataFormatException {
        final Random random = new Random(SEED);
        for (int c = 0; c < GENERATED_CASES; c++) {
            final int[] bounds = randomBox(random);
            final int positionCount = 1 + random.nextInt(200);
            final int[] positions = new int[positionCount * 3];
            final List<String> keys = new ArrayList<>();
            for (int i = 0; i < positionCount; i++) {
                for (int axis = 0; axis < 3; axis++) {
                    positions[3 * i + axis] = bounds[axis] + random.nextInt(bounds[axis + 3] - bounds[axis] + 1);
                }
                // null is air. Repeated keys share one palette entry
                keys.add(random.nextInt(5) == 0 ? null : "mod:block@" + random.nextInt(7));
            }
            assertRoundTrip(bounds, positions, keys);
        }
    }

    @Test
    void boundaryCasesDecodeToTheirLastKey() throws DataFormatException {
        assertRoundTrip(new int[] { 5, 64, -3, 5, 64, -3 }, new int[] { 5, 64, -3 }, List.of("gt.blockmachines.hull"));
        final int[] box = { -2, 10, 7, 1, 12, 9 };
        assertRoundTrip(
            box,
            new int[] { -2, 10, 7, 1, 12, 9, -2, 12, 9, 1, 10, 7 },
            List.of("a:corner@0", "a:corner@1", "a:corner@2", "a:corner@3"));
        assertRoundTrip(box, new int[] { 0, 11, 8, 0, 11, 8 }, List.of("a:first@0", "a:second@0"));
        assertRoundTrip(box, new int[] { 0, 11, 8, 1, 11, 8 }, Arrays.asList(null, null));
        final List<Integer> fullBox = new ArrayList<>();
        final List<String> fullKeys = new ArrayList<>();
        for (int x = box[0]; x <= box[3]; x++) {
            for (int y = box[1]; y <= box[4]; y++) {
                for (int z = box[2]; z <= box[5]; z++) {
                    fullBox.addAll(List.of(x, y, z));
                    fullKeys.add("a:casing@" + (x & 1));
                }
            }
        }
        assertRoundTrip(
            box,
            fullBox.stream()
                .mapToInt(Integer::intValue)
                .toArray(),
            fullKeys);
    }

    @Test
    void paletteOverOneByteDecodes() throws DataFormatException {
        final int KEY_COUNT = 300;
        final int[] bounds = { 0, 0, 0, KEY_COUNT - 1, 0, 0 };
        final int[] positions = new int[KEY_COUNT * 3];
        final List<String> keys = new ArrayList<>();
        for (int i = 0; i < KEY_COUNT; i++) {
            positions[3 * i] = i;
            keys.add("a:block@" + i);
        }
        assertRoundTrip(bounds, positions, keys);
    }

    @Test
    void boxOverTheCellLimitHasNoExtents() {
        final int side = 1 << 8;
        final int[] bounds = { 0, 0, 0, side - 1, side - 1, side - 1 };
        assertNull(ExtentCells.encodeExtents(bounds, new int[] { 0, 0, 0 }, List.of("a:block@0")));
    }

    private static int[] randomBox(Random random) {
        final int[] bounds = new int[6];
        for (int axis = 0; axis < 3; axis++) {
            bounds[axis] = random.nextInt(2001) - 1000;
            bounds[axis + 3] = bounds[axis] + random.nextInt(12);
        }
        return bounds;
    }

    /**
     * Assert that each cell decodes to the last non-null key of the cell's position, and every other cell to no block.
     */
    private static void assertRoundTrip(int[] bounds, int[] positions, List<String> keys) throws DataFormatException {
        final ExtentCells.Extents extents = ExtentCells.encodeExtents(bounds, positions, keys);
        assertNotNull(extents);
        final int sizeX = bounds[3] - bounds[0] + 1;
        final int sizeZ = bounds[5] - bounds[2] + 1;
        final Map<Integer, String> expected = new HashMap<>();
        for (int i = 0; i < keys.size(); i++) {
            if (keys.get(i) != null) {
                final int x = positions[3 * i] - bounds[0];
                final int y = positions[3 * i + 1] - bounds[1];
                final int z = positions[3 * i + 2] - bounds[2];
                expected.put((y * sizeZ + z) * sizeX + x, keys.get(i));
            }
        }
        final int[] cells = decodeCells(extents.cells(), sizeX * (bounds[4] - bounds[1] + 1) * sizeZ);
        for (int cell = 0; cell < cells.length; cell++) {
            final String key = cells[cell] == 0 ? null
                : extents.palette()
                    .get(cells[cell] - 1);
            assertEquals(expected.get(cell), key, "cell " + cell);
        }
        assertEquals(expected.size(), extents.blocks());
        assertEquals(
            extents.palette()
                .size(),
            new HashSet<>(extents.palette()).size());
    }

    private static int[] decodeCells(String text, int cellCount) throws DataFormatException {
        final Inflater inflater = new Inflater();
        inflater.setInput(
            Base64.getDecoder()
                .decode(text));
        final byte[] bytes = new byte[cellCount * 2];
        int read = 0;
        while (read < bytes.length && !inflater.finished()) {
            read += inflater.inflate(bytes, read, bytes.length - read);
        }
        inflater.end();
        assertEquals(bytes.length, read);
        final int[] cells = new int[cellCount];
        for (int i = 0; i < cellCount; i++) {
            cells[i] = (bytes[2 * i] & 0xFF) | (bytes[2 * i + 1] & 0xFF) << 8;
        }
        return cells;
    }
}
