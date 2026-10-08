package com.gtnhspeedrun.audit.compat;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The census interval check. v0.5.0 and earlier started {@code lastCensusTick} at Long.MIN_VALUE, the
 * subtraction overflowed negative, and no automatic census ever ran — the self-test missed it because it
 * fires the census through /audit snapshot, not the interval.
 */
class Ae2SnapshotterScheduleTest {

    private static final long ACTIVE = 60 * 60 * 20L;
    private static final long IDLE = 240 * 60 * 20L;

    @Test
    void freshSessionIsDueAfterExactlyOneInterval() {
        final long start = Ae2Snapshotter.INITIAL_LAST_CENSUS_TICK;
        assertFalse(Ae2Snapshotter.censusDue(ACTIVE - 1, start, ACTIVE));
        assertTrue(Ae2Snapshotter.censusDue(ACTIVE, start, ACTIVE));
    }

    @Test
    void dueAgainOneIntervalAfterACensus() {
        final long last = ACTIVE;
        assertFalse(Ae2Snapshotter.censusDue(last + ACTIVE - 1, last, ACTIVE));
        assertTrue(Ae2Snapshotter.censusDue(last + ACTIVE, last, ACTIVE));
    }

    @Test
    void idleIntervalIsLongerThanActive() {
        assertFalse(Ae2Snapshotter.censusDue(ACTIVE, 0, IDLE));
        assertTrue(Ae2Snapshotter.censusDue(IDLE, 0, IDLE));
        // A player joining mid-idle switches to the shorter interval, measured from the same last census.
        assertTrue(Ae2Snapshotter.censusDue(ACTIVE, 0, ACTIVE));
    }

    @Test
    void minValueStartNeverFires() {
        // Documents the bug: from Long.MIN_VALUE the subtraction wraps negative for every non-negative tick count.
        assertFalse(Ae2Snapshotter.censusDue(ACTIVE, Long.MIN_VALUE, ACTIVE));
        assertFalse(Ae2Snapshotter.censusDue(Long.MAX_VALUE / 2, Long.MIN_VALUE, ACTIVE));
    }
}
