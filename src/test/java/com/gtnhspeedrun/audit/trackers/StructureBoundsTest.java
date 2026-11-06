package com.gtnhspeedrun.audit.trackers;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Test that a frame has the positions of each iterateV2 call that returned true and none of each call that returned
 * false, and that the extent hash is the same for any visit order.
 */
class StructureBoundsTest {

    private static final long SEED = 20261106L;
    private static final int GENERATED_CASES = 200;
    private static final int MAX_NESTING = 4;
    private static final MachineSite CONTROLLER = new MachineSite("Controller", null, 0, 0, 64, 0);

    @BeforeEach
    void startServerOnThisThread() {
        StructureBounds.onServerStart();
    }

    @Test
    void generatedChecksKeepOnlyThePositionsOfSucceededLevels() {
        final Random random = new Random(SEED);
        for (int c = 0; c < GENERATED_CASES; c++) {
            final Object owner = new Object();
            StructureBounds.push(owner, true);
            final List<Long> expected = new ArrayList<>();
            final int topLevels = 1 + random.nextInt(3);
            for (int i = 0; i < topLevels; i++) {
                expected.addAll(runLevel(random, 1));
            }
            final int[] positions = StructureBounds.copyPositions(owner, CONTROLLER);
            if (expected.isEmpty()) {
                assertNull(positions, "case " + c);
            } else {
                assertNotNull(positions, "case " + c);
                assertEquals(sorted(expected), sorted(packAllButController(positions)), "case " + c);
            }
            StructureBounds.pop(owner);
        }
    }

    /** Run one iterateV2 level, maybe with nested levels. Return the positions of the level that stay in the frame. */
    private static List<Long> runLevel(Random random, int depth) {
        StructureBounds.iterateStarted();
        final List<Long> kept = new ArrayList<>();
        final int steps = random.nextInt(6);
        for (int s = 0; s < steps; s++) {
            if (depth < MAX_NESTING && random.nextInt(4) == 0) {
                kept.addAll(runLevel(random, depth + 1));
                continue;
            }
            final int x = random.nextInt(41) - 20;
            final int y = 50 + random.nextInt(30);
            final int z = random.nextInt(41) - 20;
            StructureBounds.visited(x, y, z);
            kept.add(pack(x, y, z));
        }
        final boolean succeeded = random.nextBoolean();
        StructureBounds.iterateFinished(succeeded);
        return succeeded ? kept : List.of();
    }

    @Test
    void hashIsTheSameForAnyVisitOrder() {
        final Random random = new Random(SEED);
        final List<int[]> visits = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            visits.add(new int[] { random.nextInt(200) - 100, random.nextInt(256), random.nextInt(200) - 100 });
        }
        final long inOrder = hashVisits(visits);
        Collections.shuffle(visits, random);
        assertEquals(inOrder, hashVisits(visits));
        visits.set(0, new int[] { 1000, 1, 1000 });
        assertNotEquals(inOrder, hashVisits(visits));
    }

    private static long hashVisits(List<int[]> visits) {
        final Object owner = new Object();
        StructureBounds.push(owner, true);
        StructureBounds.iterateStarted();
        for (int[] visit : visits) {
            StructureBounds.visited(visit[0], visit[1], visit[2]);
        }
        StructureBounds.iterateFinished(true);
        final long hash = StructureBounds.computeExtentHash(owner);
        StructureBounds.pop(owner);
        return hash;
    }

    @Test
    void checksWithNoKeptPositionHaveNoPositionsAndHashZero() {
        final Object owner = new Object();
        StructureBounds.push(owner, true);
        StructureBounds.iterateStarted();
        StructureBounds.iterateFinished(true);
        assertNull(StructureBounds.copyPositions(owner, CONTROLLER), "an empty level");
        StructureBounds.iterateStarted();
        StructureBounds.visited(1, 2, 3);
        StructureBounds.iterateFinished(false);
        assertNull(StructureBounds.copyPositions(owner, CONTROLLER), "a level that failed at depth 1");
        assertEquals(0, StructureBounds.computeExtentHash(owner));
        StructureBounds.pop(owner);
    }

    @Test
    void positionsEndWithTheController() {
        final Object owner = new Object();
        StructureBounds.push(owner, true);
        StructureBounds.iterateStarted();
        StructureBounds.visited(4, 70, -2);
        StructureBounds.iterateFinished(true);
        assertArrayEquals(
            new int[] { 4, 70, -2, CONTROLLER.x(), CONTROLLER.y(), CONTROLLER.z() },
            StructureBounds.copyPositions(owner, CONTROLLER));
        StructureBounds.pop(owner);
    }

    @Test
    void positionsPastTheCapAreDroppedAndTheBoxStays() {
        final Object owner = new Object();
        StructureBounds.push(owner, true);
        StructureBounds.iterateStarted();
        for (int i = 0; i <= StructureBounds.MAX_POSITIONS; i++) {
            StructureBounds.visited(i % 1000, 64, i / 1000);
        }
        StructureBounds.iterateFinished(true);
        assertNull(StructureBounds.copyPositions(owner, CONTROLLER));
        assertEquals(0, StructureBounds.computeExtentHash(owner));
        assertNotNull(StructureBounds.computeBounds(owner, CONTROLLER));
        StructureBounds.pop(owner);
    }

    private static List<Long> packAllButController(int[] positions) {
        final List<Long> packed = new ArrayList<>();
        for (int i = 0; i < positions.length - 3; i += 3) {
            packed.add(pack(positions[i], positions[i + 1], positions[i + 2]));
        }
        return packed;
    }

    private static long pack(int x, int y, int z) {
        return ((long) x << 40) ^ ((long) y << 20) ^ (z & 0xFFFFFL);
    }

    private static List<Long> sorted(List<Long> values) {
        final Long[] array = values.toArray(new Long[0]);
        Arrays.sort(array);
        return List.of(array);
    }
}
