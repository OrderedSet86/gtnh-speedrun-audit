package com.gtnhspeedrun.audit.core;

import java.util.concurrent.atomic.AtomicLong;

/**
 * The run clocks. Both are cumulative across every session of this world, seeded from the anchor and
 * persisted back through it, so they rewind with a backup restore and replayed time counts once:
 *
 * <ul>
 * <li>IGT — every server tick after timing starts (the board's main metric; AFK machine time counts)</li>
 * <li>player-online ticks — only ticks with at least one player connected</li>
 * </ul>
 *
 * RTA needs no counter: it is the wall-clock span since the anchored timing_started moment, and every log
 * line carries a wall timestamp. Neither counter moves until the first player movement (worldgen lag and
 * staring at spawn don't count).
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
}
