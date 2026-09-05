package com.gtnhspeedrun.audit.core;

import java.util.concurrent.atomic.AtomicLong;

/**
 * The run clock: cumulative server ticks across every session of this world. Seeded from the anchor at session
 * start, incremented once per server tick, persisted back through the anchor. Rolls back with the world on a
 * backup restore, so replayed time is counted once — wall-clock timestamps are the other half of the story and
 * come straight from {@link System#currentTimeMillis()} at log time.
 */
public final class TickClock {

    private final AtomicLong ticks;

    public TickClock(long start) {
        this.ticks = new AtomicLong(start);
    }

    public long increment() {
        return ticks.incrementAndGet();
    }

    public long get() {
        return ticks.get();
    }
}
