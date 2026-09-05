package com.gtnhspeedrun.audit.core;

import java.util.function.Supplier;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Producer-side facade over the writer. Safe from any thread — commands arrive on the RCON thread, NEI cheats
 * on the netty thread — because it only captures timestamps and enqueues.
 */
public final class AuditLogger {

    private final LogWriter writer;
    private final TickClock clock;

    public AuditLogger(LogWriter writer, TickClock clock) {
        this.writer = writer;
        this.clock = clock;
    }

    public void log(String type, JsonObject data) {
        writer.submit(
            new PendingEvent(type, data, System.currentTimeMillis(), clock.get(), Thread.currentThread().getName()));
    }

    /**
     * The supplier runs on the writer thread — it must close over an immutable struct captured beforehand,
     * never over live game state.
     */
    public void logSnapshot(String type, String fileName, Supplier<JsonElement> content, JsonObject refData) {
        writer.submitSnapshot(type, fileName, content, refData, System.currentTimeMillis(), clock.get());
    }

    public TickClock clock() {
        return clock;
    }

    public LogWriter writer() {
        return writer;
    }
}
