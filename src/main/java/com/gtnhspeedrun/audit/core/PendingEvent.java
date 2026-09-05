package com.gtnhspeedrun.audit.core;

import com.google.gson.JsonObject;

/**
 * An event captured on a game/network thread, waiting for the writer thread to assign it a seq and chain it.
 * The data tree must not be touched by the producer after handoff — build it fresh, pass it, drop the reference.
 */
public final class PendingEvent {

    public final String type;
    public final JsonObject data;
    public final long wallMs;
    public final long ticks;
    public final String thread;

    public PendingEvent(String type, JsonObject data, long wallMs, long ticks, String thread) {
        this.type = type;
        this.data = data;
        this.wallMs = wallMs;
        this.ticks = ticks;
        this.thread = thread;
    }
}
