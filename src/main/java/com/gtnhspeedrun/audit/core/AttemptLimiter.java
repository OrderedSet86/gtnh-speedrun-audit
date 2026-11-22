package com.gtnhspeedrun.audit.core;

import java.time.Duration;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Limit the log lines of a repeated action. When an identity has more than {@code linesPerWindow} lines in one window,
 * the other attempts are counted, and the count is logged in one line of the report type when the window ends. For a
 * flood, {@code AttemptLimiter} then logs one report line per window, and the report line has the count of every
 * dropped attempt.
 *
 * <p>
 * The report line has the identity's fields, then {@code dropped} and {@code windowMs}:
 *
 * <pre>
 *     {"uuid":"…","name":"Steve","type":"nbt_edit","dropped":4180,"windowMs":60000}
 *     {"senderType":"commandblock","sender":"@","dim":0,"pos":[184,104,-112],"cmd":"testfor","args":["@a"],
 *      "dropped":24,"windowMs":600000}
 * </pre>
 */
public final class AttemptLimiter {

    public static final int PLAYER_LINES_PER_WINDOW = 20;

    private final AuditLogger logger;
    private final long windowNanos;
    private final int linesPerWindow;
    private final String reportType;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    public AttemptLimiter(AuditLogger logger, Limit limit) {
        this.logger = logger;
        this.windowNanos = limit.window()
            .toNanos();
        this.linesPerWindow = limit.linesPerWindow();
        this.reportType = limit.reportType();
    }

    /**
     * Return whether one attempt of {@code type} by the player may have a separate line. An attempt past the limit is
     * counted instead. Call from any thread.
     */
    public boolean tryLog(String playerUuid, String playerName, String type) {
        final JsonObject identity = new JsonObject();
        identity.addProperty("uuid", playerUuid);
        identity.addProperty("name", playerName);
        identity.addProperty("type", type);
        return tryLog(identity);
    }

    /**
     * Return whether one attempt with {@code identity} may have a separate line. An attempt past the limit is counted
     * instead. Two identities are the same when their JSON text is the same. Call from any thread, and don't change
     * {@code identity} after the call.
     */
    public boolean tryLog(JsonObject identity) {
        final long nowNanos = System.nanoTime();
        final Window window = windows.computeIfAbsent(identity.toString(), unused -> new Window(identity, nowNanos));
        synchronized (window) {
            if (nowNanos - window.startNanos >= windowNanos) {
                reportAndRestart(window, nowNanos);
            }
            if (window.lines < linesPerWindow) {
                window.lines++;
                return true;
            }
            window.dropped++;
            return false;
        }
    }

    /**
     * Log the report line of each window that has ended with attempts dropped, and forget the windows that have ended.
     * Call about once a second, so a flood that stops is still reported.
     */
    public void reportEndedWindows() {
        reportWindowsOlderThan(windowNanos);
    }

    /**
     * Log the report line of every window with attempts dropped, whether or not the window has ended. Call at a session
     * stop.
     */
    public void reportAllWindows() {
        reportWindowsOlderThan(0);
    }

    private void reportWindowsOlderThan(long minAgeNanos) {
        final long nowNanos = System.nanoTime();
        final Iterator<Map.Entry<String, Window>> entries = windows.entrySet()
            .iterator();
        while (entries.hasNext()) {
            final Window window = entries.next()
                .getValue();
            synchronized (window) {
                if (nowNanos - window.startNanos < minAgeNanos) {
                    continue;
                }
                reportAndRestart(window, nowNanos);
                entries.remove();
            }
        }
    }

    /** Log the count of {@code window} when the count isn't 0, and start a new window at {@code nowNanos}. */
    private void reportAndRestart(Window window, long nowNanos) {
        if (window.dropped > 0) {
            final JsonObject data = new JsonObject();
            for (Map.Entry<String, JsonElement> field : window.identity.entrySet()) {
                data.add(field.getKey(), field.getValue());
            }
            data.addProperty("dropped", window.dropped);
            data.addProperty("windowMs", windowNanos / 1_000_000);
            logger.log(reportType, data);
        }
        window.startNanos = nowNanos;
        window.lines = 0;
        window.dropped = 0;
    }

    /**
     * At most {@code linesPerWindow} lines per identity in each {@code window}. A {@code reportType} line has the count
     * of the other attempts.
     */
    public record Limit(Duration window, int linesPerWindow, String reportType) {}

    /**
     * The lines and dropped attempts of one identity since {@code startNanos}. {@code AttemptLimiter} reads and changes
     * these fields only inside {@code synchronized (window)}.
     */
    private static final class Window {

        private final JsonObject identity;
        private long startNanos;
        private int lines;
        private long dropped;

        private Window(JsonObject identity, long startNanos) {
            this.identity = identity;
            this.startNanos = startNanos;
        }
    }
}
