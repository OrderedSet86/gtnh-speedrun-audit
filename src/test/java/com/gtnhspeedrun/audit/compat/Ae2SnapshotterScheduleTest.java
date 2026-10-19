package com.gtnhspeedrun.audit.compat;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Test the census interval check. A start of {@code Long.MIN_VALUE} for {@code lastCensusTick} overflows the
 * subtraction, and no automatic census runs. The self-test fires the census through /audit snapshot and never waits
 * for the interval.
 */
class Ae2SnapshotterScheduleTest {

    private static final long ACTIVE_TICKS = 60 * 60 * 20L;
    private static final long IDLE_TICKS = 240 * 60 * 20L;

    @Test
    void freshSessionIsDueAfterExactlyOneInterval() {
        final long start = Ae2Snapshotter.INITIAL_LAST_CENSUS_TICK;
        assertFalse(Ae2Snapshotter.isCensusDue(ACTIVE_TICKS - 1, start, ACTIVE_TICKS));
        assertTrue(Ae2Snapshotter.isCensusDue(ACTIVE_TICKS, start, ACTIVE_TICKS));
    }

    @Test
    void dueAgainOneIntervalAfterACensus() {
        final long last = ACTIVE_TICKS;
        assertFalse(Ae2Snapshotter.isCensusDue(last + ACTIVE_TICKS - 1, last, ACTIVE_TICKS));
        assertTrue(Ae2Snapshotter.isCensusDue(last + ACTIVE_TICKS, last, ACTIVE_TICKS));
    }

    @Test
    void idleIntervalIsLongerThanActive() {
        assertFalse(Ae2Snapshotter.isCensusDue(ACTIVE_TICKS, 0, IDLE_TICKS));
        assertTrue(Ae2Snapshotter.isCensusDue(IDLE_TICKS, 0, IDLE_TICKS));
        // a player joining mid-idle switches to the shorter interval, measured from the same last census
        assertTrue(Ae2Snapshotter.isCensusDue(ACTIVE_TICKS, 0, ACTIVE_TICKS));
    }

    @Test
    void minValueStartNeverFires() {
        // from Long.MIN_VALUE the subtraction wraps negative for every non-negative tick count
        assertFalse(Ae2Snapshotter.isCensusDue(ACTIVE_TICKS, Long.MIN_VALUE, ACTIVE_TICKS));
        assertFalse(Ae2Snapshotter.isCensusDue(Long.MAX_VALUE / 2, Long.MIN_VALUE, ACTIVE_TICKS));
    }
}
