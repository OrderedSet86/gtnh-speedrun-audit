package com.gtnhspeedrun.audit.trackers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

/** Test the rule that skips a still player's row, the yaw gap, and the JSON form of a row. */
class PositionRowTest {

    private static final int CASES = 2_000;
    private static final List<String> MODES = List.of("s", "c", "a", "", "spectator");

    @Test
    void moveWithinTheLimitOnEveryAxisAndAngleIsStill() {
        final Random random = new Random(41);
        for (int i = 0; i < CASES; i++) {
            final PositionRow last = generateRow(random);
            final int limit = PositionRow.STILL_TENTHS;
            final int angleLimit = PositionRow.STILL_DEGREES;
            final PositionRow row = moveRow(
                last,
                new int[] { offset(random, limit), offset(random, limit), offset(random, limit) },
                offset(random, angleLimit),
                offset(random, angleLimit));
            assertTrue(row.isStillFrom(last), row + " from " + last);
        }
        // the boundary values: no move, and the full limit on every axis and angle
        final PositionRow origin = new PositionRow(0, "s", 0, 0, 0, 0, 0, 0);
        assertTrue(origin.isStillFrom(origin));
        final int limit = PositionRow.STILL_TENTHS;
        final int angleLimit = PositionRow.STILL_DEGREES;
        assertTrue(moveRow(origin, new int[] { limit, -limit, limit }, angleLimit, -angleLimit).isStillFrom(origin));
    }

    @Test
    void moveOnePastTheLimitOnAnyAxisOrAngleIsNotStill() {
        final Random random = new Random(42);
        for (int i = 0; i < CASES; i++) {
            final PositionRow last = generateRow(random);
            final int past = PositionRow.STILL_TENTHS + 1 + random.nextInt(10_000);
            final int[] move = new int[3];
            move[random.nextInt(3)] = random.nextBoolean() ? past : -past;
            assertFalse(moveRow(last, move, 0, 0).isStillFrom(last), "move " + past + " from " + last);
            final int pastAngle = PositionRow.STILL_DEGREES + 1 + random.nextInt(170 - PositionRow.STILL_DEGREES);
            assertFalse(moveRow(last, new int[3], pastAngle, 0).isStillFrom(last), "yaw +" + pastAngle);
        }
        // the boundary values: one tenth past the limit on each axis, one degree past on each angle
        final PositionRow origin = new PositionRow(0, "s", 0, 0, 0, 0, 0, 0);
        final int past = PositionRow.STILL_TENTHS + 1;
        assertFalse(moveRow(origin, new int[] { past, 0, 0 }, 0, 0).isStillFrom(origin));
        assertFalse(moveRow(origin, new int[] { 0, -past, 0 }, 0, 0).isStillFrom(origin));
        assertFalse(moveRow(origin, new int[] { 0, 0, past }, 0, 0).isStillFrom(origin));
        assertFalse(moveRow(origin, new int[3], PositionRow.STILL_DEGREES + 1, 0).isStillFrom(origin));
        assertFalse(moveRow(origin, new int[3], 0, -(PositionRow.STILL_DEGREES + 1)).isStillFrom(origin));
    }

    @Test
    void changeOfDimensionOrModeIsNeverStill() {
        final Random random = new Random(43);
        for (int i = 0; i < CASES; i++) {
            final PositionRow last = generateRow(random);
            final PositionRow otherDim = new PositionRow(
                last.player(),
                last.mode(),
                last.dim() + 1 + random.nextInt(100),
                last.xTenths(),
                last.yTenths(),
                last.zTenths(),
                last.yaw(),
                last.pitch());
            assertFalse(otherDim.isStillFrom(last), otherDim + " from " + last);
            final String otherMode = MODES.get((MODES.indexOf(last.mode()) + 1) % MODES.size());
            final PositionRow modeChanged = new PositionRow(
                last.player(),
                otherMode,
                last.dim(),
                last.xTenths(),
                last.yTenths(),
                last.zTenths(),
                last.yaw(),
                last.pitch());
            assertFalse(modeChanged.isStillFrom(last), modeChanged + " from " + last);
        }
    }

    @Test
    void yawGapIsSymmetricAndAtMostAHalfTurn() {
        final Random random = new Random(44);
        for (int i = 0; i < CASES; i++) {
            final int yaw = random.nextInt(360) - 180;
            final int otherYaw = random.nextInt(360) - 180;
            final int gap = PositionRow.computeYawGap(yaw, otherYaw);
            assertEquals(gap, PositionRow.computeYawGap(otherYaw, yaw));
            assertTrue(gap >= 0 && gap <= 180, yaw + " to " + otherYaw + " = " + gap);
        }
        // the boundary values: across the -180/180 seam, a half turn, and no turn
        assertEquals(2, PositionRow.computeYawGap(179, -179));
        assertEquals(1, PositionRow.computeYawGap(-180, 179));
        assertEquals(180, PositionRow.computeYawGap(90, -90));
        assertEquals(0, PositionRow.computeYawGap(-180, -180));
    }

    @Test
    void rowSurvivesTheJsonRoundTrip() {
        final Random random = new Random(45);
        for (int i = 0; i < CASES; i++) {
            final PositionRow row = generateRow(random);
            assertEquals(row, PositionRow.parseRow(row.toJson()));
        }
        // the boundary values: the extreme yaw and pitch, and a world border position
        final PositionRow extreme = new PositionRow(0, "", Integer.MIN_VALUE, -300_000_000, 0, 300_000_000, -180, 90);
        assertEquals(extreme, PositionRow.parseRow(extreme.toJson()));
    }

    private static PositionRow generateRow(Random random) {
        final int WORLD_TENTHS = 300_000_000;
        return new PositionRow(
            random.nextInt(8),
            MODES.get(random.nextInt(MODES.size())),
            random.nextInt(20) - 10,
            random.nextInt(2 * WORLD_TENTHS) - WORLD_TENTHS,
            random.nextInt(2560),
            random.nextInt(2 * WORLD_TENTHS) - WORLD_TENTHS,
            random.nextInt(360) - 180,
            random.nextInt(181) - 90);
    }

    private static int offset(Random random, int limit) {
        return random.nextInt(2 * limit + 1) - limit;
    }

    /** Return {@code row} moved by {@code move} tenths {x, y, z}, with the yaw turned and the pitch clamped. */
    private static PositionRow moveRow(PositionRow row, int[] move, int yawTurn, int pitchTurn) {
        final int HALF_TURN = 180;
        final int RIGHT_ANGLE = 90;
        final int yaw = Math.floorMod(row.yaw() + yawTurn + HALF_TURN, 2 * HALF_TURN) - HALF_TURN;
        final int pitch = Math.max(-RIGHT_ANGLE, Math.min(RIGHT_ANGLE, row.pitch() + pitchTurn));
        return new PositionRow(
            row.player(),
            row.mode(),
            row.dim(),
            row.xTenths() + move[0],
            row.yTenths() + move[1],
            row.zTenths() + move[2],
            yaw,
            pitch);
    }
}
