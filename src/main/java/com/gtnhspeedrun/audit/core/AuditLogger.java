package com.gtnhspeedrun.audit.core;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.gtnhspeedrun.audit.api.AuditEvents;

/**
 * Timestamp events on the producer thread and queue them for {@link LogWriter}. Any thread can log through this
 * class, because a call only reads the atomic clocks and queues the event. Commands arrive on the RCON thread, and
 * NEI cheat packets arrive on the netty thread. A line from the thread of the {@link ClientThreadGate} is dropped and
 * counted.
 */
public final class AuditLogger {

    private final LogWriter writer;
    private final TickClock clock;
    private final ClientThreadGate gate;
    private final AtomicLong droppedLines = new AtomicLong();

    public AuditLogger(LogWriter writer, TickClock clock, ClientThreadGate gate) {
        this.writer = writer;
        this.clock = clock;
        this.gate = gate;
    }

    public void log(String type, JsonObject data) {
        if (gate.isGated(Thread.currentThread())) {
            droppedLines.incrementAndGet();
            return;
        }
        final TickClock.Reading reading = clock.read();
        final boolean queued = writer.submit(
            new PendingEvent(
                type,
                data,
                reading,
                Thread.currentThread()
                    .getName()));
        // other mods get only the events that go into the log
        if (queued) {
            AuditEvents.publish(type, data, reading.ticks());
        }
    }

    public void logSnapshot(Snapshot snapshot) {
        writer.submitSnapshot(
            new LogWriter.SnapshotJob(
                snapshot.type(),
                snapshot.fileStem(),
                snapshot.content(),
                snapshot.refData(),
                clock.read()));
    }

    public long countDroppedLines() {
        return droppedLines.get();
    }

    public TickClock clock() {
        return clock;
    }

    public LogWriter writer() {
        return writer;
    }

    /**
     * A snapshot file and the reference line for the file.
     *
     * @param fileStem the file name without the {@code -<seq>.json.gz} that {@link LogWriter} adds
     * @param content  runs on the writer thread. The supplier must close over a copy captured beforehand, NEVER over
     *                 live game state.
     */
    public record Snapshot(String type, String fileStem, Supplier<JsonElement> content, JsonObject refData) {}
}
