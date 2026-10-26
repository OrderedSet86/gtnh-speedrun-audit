package com.gtnhspeedrun.audit.snapshot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;

import org.junit.jupiter.api.Test;

/** Test that a logged coordinate or angle is the nearest tenth of the value. */
class PlayerPlaceTest {

    private static final double HALF_TENTH = 0.05;
    private static final double EPSILON = 1e-9;

    @Test
    void roundedValueIsAWholeTenthWithinHalfATenth() {
        final Random random = new Random(40);
        final int CASES = 10_000;
        for (int i = 0; i < CASES; i++) {
            // the 1.7.10 world border is 30,000,000 blocks from the origin
            assertRoundedToTenth((random.nextDouble() - 0.5) * 60_000_000);
            assertRoundedToTenth((random.nextDouble() - 0.5) * 360);
        }
        // the boundary values: zero, the halfway points, and the ends of the yaw and pitch ranges
        for (double value : new double[] { 0.0, -0.0, 0.05, -0.05, 0.15, 179.95, -180.0, 90.0, -90.0, 30_000_000.0,
            -30_000_000.0 }) {
            assertRoundedToTenth(value);
        }
    }

    private static void assertRoundedToTenth(double value) {
        final double rounded = PlayerPlace.roundToTenth(value);
        assertTrue(Math.abs(rounded - value) <= HALF_TENTH + EPSILON, value + " -> " + rounded);
        assertEquals(
            Math.rint(rounded * 10),
            rounded * 10,
            EPSILON * Math.max(1, Math.abs(rounded)),
            value + " -> " + rounded);
    }
}
