package com.gtnhspeedrun.audit.core;

import java.time.Duration;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.google.gson.JsonObject;

/**
 * Limit the log lines of client actions that have no effect: packets that a permission check rejects after the audit's
 * hook, such as a forged /nbtedit reply or an NEI packet. Past {@link #LINES_PER_WINDOW} lines per player and type in
 * one window, attempts are counted, and the count is logged in a {@code rate_limited} line when the window ends. A
 * flood from one client then costs one line per window, and every attempt stays on record.
 *
 * <pre>
 *     {"uuid":"…","name":"Steve","type":"nbt_edit","dropped":4180,"windowMs":60000}
 * </pre>
 */
public final class AttemptLimiter {

    public static final int LINES_PER_WINDOW = 20;

    private final AuditLogger logger;
    private final long windowNanos;
    private final Map<Key, Window> windows = new ConcurrentHashMap<>();

    public AttemptLimiter(AuditLogger logger, Duration window) {
        this.logger = logger;
        this.windowNanos = window.toNanos();
    }

    /**
     * Return whether one attempt of {@code type} by the player may have a separate line. An attempt past the limit is
     * counted instead. Call from any thread.
     */
    public boolean tryLog(String playerUuid, String playerName, String type) {
        final long nowNanos = System.nanoTime();
        final Key key = new Key(playerUuid, type);
        final Window window = windows.computeIfAbsent(key, unused -> new Window(playerName, nowNanos));
        synchronized (window) {
            if (nowNanos - window.startNanos >= windowNanos) {
                reportAndRestart(key, window, nowNanos);
            }
            if (window.lines < LINES_PER_WINDOW) {
                window.lines++;
                return true;
            }
            window.dropped++;
            return false;
        }
    }

    /**
     * Log the {@code rate_limited} line of each window that has ended with attempts dropped, and forget the windows
     * that have ended. Call about once a second, so a flood that stops is still reported.
     */
    public void reportEndedWindows() {
        reportWindowsOlderThan(windowNanos);
    }

    /**
     * Log the {@code rate_limited} line of every window with attempts dropped, ended or not. Call at a session stop.
     */
    public void reportAllWindows() {
        reportWindowsOlderThan(0);
    }

    private void reportWindowsOlderThan(long minAgeNanos) {
        final long nowNanos = System.nanoTime();
        final Iterator<Map.Entry<Key, Window>> entries = windows.entrySet()
            .iterator();
        while (entries.hasNext()) {
            final Map.Entry<Key, Window> entry = entries.next();
            final Window window = entry.getValue();
            synchronized (window) {
                if (nowNanos - window.startNanos < minAgeNanos) {
                    continue;
                }
                reportAndRestart(entry.getKey(), window, nowNanos);
                entries.remove();
            }
        }
    }

    /** Log the count of {@code window} when the count isn't 0, and start a new window at {@code nowNanos}. */
    private void reportAndRestart(Key key, Window window, long nowNanos) {
        if (window.dropped > 0) {
            final JsonObject data = new JsonObject();
            data.addProperty("uuid", key.playerUuid());
            data.addProperty("name", window.playerName);
            data.addProperty("type", key.type());
            data.addProperty("dropped", window.dropped);
            data.addProperty("windowMs", windowNanos / 1_000_000);
            logger.log("rate_limited", data);
        }
        window.startNanos = nowNanos;
        window.lines = 0;
        window.dropped = 0;
    }

    private record Key(String playerUuid, String type) {}

    /** The lines and dropped attempts of one player and type since {@code startNanos}. Guarded by the window itself. */
    private static final class Window {

        private final String playerName;
        private long startNanos;
        private int lines;
        private long dropped;

        private Window(String playerName, long startNanos) {
            this.playerName = playerName;
            this.startNanos = startNanos;
        }
    }
}
