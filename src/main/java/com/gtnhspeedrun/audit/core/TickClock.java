package com.gtnhspeedrun.audit.core;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Count the two run clocks of the world. Both clocks add up over every session. IGT (in-game time) counts every
 * server tick after timing starts, AFK machine time included, and is the main metric of the board. The player-online
 * clock counts only the ticks with at least one player connected. RTA (real time) has no counter, because every log
 * line has a wall-clock timestamp.
 */
public final class TickClock {

    private final AtomicLong ticks;
    private final AtomicLong onlineTicks;

    public TickClock(long startTicks, long startOnlineTicks) {
        this.ticks = new AtomicLong(startTicks);
        this.onlineTicks = new AtomicLong(startOnlineTicks);
    }

    public long increment(boolean anyoneOnline) {
        if (anyoneOnline) {
            onlineTicks.incrementAndGet();
        }
        return ticks.incrementAndGet();
    }

    public long get() {
        return ticks.get();
    }

    public long getOnline() {
        return onlineTicks.get();
    }

    public Reading read() {
        return new Reading(System.currentTimeMillis(), ticks.get(), onlineTicks.get());
    }

    public record Reading(long wallMs, long ticks, long onlineTicks) {}
}
