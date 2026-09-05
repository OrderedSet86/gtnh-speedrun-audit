package com.gtnhspeedrun.audit.core;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.zip.GZIPOutputStream;

import org.apache.logging.log4j.Logger;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * The single writer thread. Everything that reaches disk goes through here, in queue order: seq assignment,
 * envelope construction, hash chaining and the append are all serialized on one thread, so the chain never
 * needs a lock. Producers only pay for building a small JSON tree.
 *
 * <p>
 * Snapshot jobs carry a supplier over an immutable struct captured on the server thread; the expensive part —
 * building and gzipping a possibly 20k-entry JSON tree — runs here. The snapshot file is written and hashed
 * BEFORE its reference line is appended, so a line in the chain always vouches for bytes already on disk.
 */
public final class LogWriter implements Runnable {

    /** Durability floor for the interesting lines; everything else rides the periodic force. */
    private static final long FORCE_INTERVAL_MS = 5000;

    private final Logger log;
    private final File logFile;
    private final File snapshotDir;
    private final String sessionId;
    private final int sessionIndex;
    private final LinkedBlockingQueue<Object> queue = new LinkedBlockingQueue<>();
    private final Thread thread;

    /** Intra-session split so one marathon 24/7 session can't grow a single unwieldy file. Chain-neutral. */
    private static final long MAX_FILE_BYTES = 50L * 1024 * 1024;

    private FileOutputStream out;
    private Writer writer;
    private long lastForceMs;
    private long bytesWritten;
    private int partIndex = 1;

    private long seq;
    private String head;
    private volatile long publishedSeq;
    private volatile String publishedHead;
    private volatile boolean dead;

    private static final Object POISON = new Object();

    private static final class SnapshotJob {

        final String type;
        final String fileName;
        final Supplier<JsonElement> content;
        final JsonObject refData;
        final long wallMs;
        final long ticks;

        SnapshotJob(String type, String fileName, Supplier<JsonElement> content, JsonObject refData, long wallMs,
            long ticks) {
            this.type = type;
            this.fileName = fileName;
            this.content = content;
            this.refData = refData;
            this.wallMs = wallMs;
            this.ticks = ticks;
        }
    }

    public LogWriter(Logger log, File logFile, File snapshotDir, String sessionId, int sessionIndex, long startSeq,
        String startHead) {
        this.log = log;
        this.logFile = logFile;
        this.snapshotDir = snapshotDir;
        this.sessionId = sessionId;
        this.sessionIndex = sessionIndex;
        this.seq = startSeq;
        this.head = startHead;
        this.publishedSeq = startSeq;
        this.publishedHead = startHead;
        this.thread = new Thread(this, "SpeedrunAudit-Writer");
        this.thread.setDaemon(true);
    }

    public void start() throws IOException {
        open(logFile);
        thread.start();
    }

    private void open(File file) throws IOException {
        // Append mode: a session file survives writer restarts within one JVM; new sessions get new files.
        bytesWritten = file.length();
        out = new FileOutputStream(file, true);
        writer = new OutputStreamWriter(out, StandardCharsets.UTF_8);
    }

    /** Writer thread only. The chain is file-agnostic, so a split is invisible to verification. */
    private void rollIfNeeded() throws IOException {
        if (bytesWritten < MAX_FILE_BYTES) {
            return;
        }
        finish();
        partIndex++;
        final String base = logFile.getName()
            .substring(
                0,
                logFile.getName()
                    .length() - ".jsonl".length());
        open(new File(logFile.getParentFile(), base + "-p" + partIndex + ".jsonl"));
    }

    public void submit(PendingEvent event) {
        if (!dead) {
            queue.add(event);
        }
    }

    public void submitSnapshot(String type, String fileName, Supplier<JsonElement> content, JsonObject refData,
        long wallMs, long ticks) {
        if (!dead) {
            queue.add(new SnapshotJob(type, fileName, content, refData, wallMs, ticks));
        }
    }

    /** Last seq/hash actually on disk; read by the anchor updater on the server thread. */
    public long publishedSeq() {
        return publishedSeq;
    }

    public String publishedHead() {
        return publishedHead;
    }

    public boolean isDead() {
        return dead;
    }

    public int queueDepth() {
        return queue.size();
    }

    /** Blocks until everything queued so far is on disk (or the timeout passes). */
    public boolean drain(long timeoutMs) {
        final long deadline = System.currentTimeMillis() + timeoutMs;
        while (!queue.isEmpty() && !dead && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread()
                    .interrupt();
                return false;
            }
        }
        return queue.isEmpty();
    }

    public void close(long timeoutMs) {
        queue.add(POISON);
        try {
            thread.join(timeoutMs);
        } catch (InterruptedException e) {
            Thread.currentThread()
                .interrupt();
        }
    }

    @Override
    public void run() {
        try {
            while (true) {
                final Object job = queue.poll(1, TimeUnit.SECONDS);
                if (job == POISON) {
                    break;
                }
                if (job instanceof PendingEvent event) {
                    appendLine(event.type, event.data, event.wallMs, event.ticks, event.thread);
                } else if (job instanceof SnapshotJob snap) {
                    writeSnapshot(snap);
                } else if (job == null) {
                    maybeForce(false);
                }
            }
            finish();
        } catch (Throwable t) {
            // Disk full, closed stream, etc. Producers keep the game alive; /audit status surfaces the death.
            dead = true;
            log.error("Audit writer died — logging has STOPPED", t);
        }
    }

    private void writeSnapshot(SnapshotJob snap) throws IOException {
        final File file = new File(snapshotDir, snap.fileName);
        final byte[] json = JsonUtil.GSON.toJson(snap.content.get())
            .getBytes(StandardCharsets.UTF_8);
        try (GZIPOutputStream gz = new GZIPOutputStream(new FileOutputStream(file))) {
            gz.write(json);
        }
        final byte[] gzBytes = java.nio.file.Files.readAllBytes(file.toPath());
        snap.refData.addProperty("file", snap.fileName);
        snap.refData.addProperty("fileSha256", JsonUtil.sha256Hex(gzBytes));
        snap.refData.addProperty("rawBytes", json.length);
        appendLine(snap.type, snap.refData, snap.wallMs, snap.ticks, "writer");
    }

    private void appendLine(String type, JsonObject data, long wallMs, long ticks, String producerThread)
        throws IOException {
        final JsonObject line = new JsonObject();
        line.addProperty("v", 1);
        line.addProperty("seq", seq);
        line.addProperty("sid", sessionId);
        line.addProperty("sidx", sessionIndex);
        line.addProperty("t", type);
        line.addProperty("wall", wallMs);
        line.addProperty("ticks", ticks);
        line.addProperty("thread", producerThread);
        line.add("data", data);
        line.addProperty("prev", head);

        final String json = JsonUtil.GSON.toJson(line);
        final byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        writer.write(json);
        writer.write('\n');
        writer.flush();
        bytesWritten += bytes.length + 1;

        head = JsonUtil.sha256Hex(bytes);
        publishedHead = head;
        publishedSeq = seq;
        seq++;

        maybeForce(isUrgent(type));
        rollIfNeeded();
    }

    private static boolean isUrgent(String type) {
        return type.startsWith("session_") || type.equals("command")
            || type.equals("nei_cheat")
            || type.equals("gamemode_change");
    }

    private void maybeForce(boolean urgent) throws IOException {
        final long now = System.currentTimeMillis();
        if (urgent || now - lastForceMs > FORCE_INTERVAL_MS) {
            writer.flush();
            out.getChannel()
                .force(false);
            lastForceMs = now;
        }
    }

    private void finish() throws IOException {
        writer.flush();
        out.getChannel()
            .force(false);
        writer.close();
    }
}
