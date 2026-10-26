package com.gtnhspeedrun.audit.trackers;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Random;

import net.minecraft.world.WorldSettings;

import org.junit.jupiter.api.Test;

/** Test the sample interval of each gamemode, and the sample count of an interval over a span of ticks. */
class PositionSamplerTest {

    @Test
    void onlySurvivalAndNotSetTakeTheSurvivalInterval() {
        final PositionSampler.Intervals intervals = PositionSampler.Intervals.ofSeconds(30, 5);
        for (WorldSettings.GameType gameType : WorldSettings.GameType.values()) {
            final boolean survival = gameType == WorldSettings.GameType.SURVIVAL
                || gameType == WorldSettings.GameType.NOT_SET;
            final long expected = survival ? 600 : 100;
            assertEquals(expected, intervals.ticksFor(gameType), gameType.getName());
        }
    }

    @Test
    void spanOfTicksHasOneSamplePerInterval() {
        final Random random = new Random(28);
        final int CASES = 200;
        for (int i = 0; i < CASES; i++) {
            final int survivalSeconds = 1 + random.nextInt(3600);
            final int otherSeconds = 1 + random.nextInt(3600);
            final long spanTicks = 1 + random.nextInt(20_000);
            assertSamplesPerSpan(PositionSampler.Intervals.ofSeconds(survivalSeconds, otherSeconds), spanTicks);
        }
        // the boundary values: the shortest and longest config intervals, and spans one tick either side of a multiple
        assertSamplesPerSpan(PositionSampler.Intervals.ofSeconds(1, 1), 1);
        assertSamplesPerSpan(PositionSampler.Intervals.ofSeconds(3600, 3600), 72_000 - 1);
        assertSamplesPerSpan(PositionSampler.Intervals.ofSeconds(3600, 3600), 72_000);
        assertSamplesPerSpan(PositionSampler.Intervals.ofSeconds(30, 5), 600 + 1);
    }

    /**
     * Count the due ticks in {@code 1..spanTicks} for each gamemode, and compare with the whole intervals in the span.
     */
    private static void assertSamplesPerSpan(PositionSampler.Intervals intervals, long spanTicks) {
        for (WorldSettings.GameType gameType : WorldSettings.GameType.values()) {
            final long intervalTicks = intervals.ticksFor(gameType);
            long due = 0;
            for (long tick = 1; tick <= spanTicks; tick++) {
                if (intervals.isDue(tick, gameType)) {
                    due++;
                }
            }
            assertEquals(spanTicks / intervalTicks, due, intervals + " span=" + spanTicks + " " + gameType.getName());
        }
    }
}
