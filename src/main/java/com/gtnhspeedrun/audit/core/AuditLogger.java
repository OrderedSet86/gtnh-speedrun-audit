package com.gtnhspeedrun.audit.core;

import java.util.function.Supplier;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Timestamp events on the producer thread and queue them for {@link LogWriter}. Any thread can log through this
 * class, because a call only reads the atomic clocks and queues the event. Commands arrive on the RCON thread, and
 * NEI cheat packets arrive on the netty thread.
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
            new PendingEvent(
                type,
                data,
                clock.read(),
                Thread.currentThread()
                    .getName()));
    }

    public void logSnapshot(Snapshot snapshot) {
        writer.submitSnapshot(
            new LogWriter.SnapshotJob(
                snapshot.type(),
                snapshot.fileName(),
                snapshot.content(),
                snapshot.refData(),
                clock.read()));
    }

    public TickClock clock() {
        return clock;
    }

    public LogWriter writer() {
        return writer;
    }

    /**
     * A snapshot file and its reference line.
     *
     * @param content runs on the writer thread. The supplier must close over a copy captured beforehand, NEVER over
     *                live game state.
     */
    public record Snapshot(String type, String fileName, Supplier<JsonElement> content, JsonObject refData) {}
}
