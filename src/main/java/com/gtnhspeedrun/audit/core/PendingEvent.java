package com.gtnhspeedrun.audit.core;

import com.google.gson.JsonObject;

/**
 * An event captured on a game or network thread, queued for the writer thread. The writer thread assigns the event a
 * seq and adds the event to the chain. Build {@code data} fresh for each event, and NEVER change {@code data} after
 * the handoff.
 */
public record PendingEvent(String type, JsonObject data, TickClock.Reading clocks, String threadName) {}
