package com.gtnhspeedrun.audit.core;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.zip.GZIPOutputStream;

import org.apache.logging.log4j.Logger;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Write every line of the audit log on one thread, in queue order. Seq assignment, the envelope, the hash chain and
 * the append run on this thread only, so the chain state has no lock. On a producer thread, the cost of an event is
 * the build of a small JSON tree.
 *
 * <p>
 * A snapshot job has a supplier over data captured on the server thread. This thread runs the supplier and gzips the
 * result. A supplier can build a JSON tree of 20k entries. The snapshot file is written and hashed before its
 * reference line is appended, so every line in the chain refers to bytes already written.
 */
public final class LogWriter implements Runnable {

    // the longest time between two channel.force() calls while lines arrive. An isUrgent() line is forced at once
    private static final long FORCE_INTERVAL_MS = 5000;
    // the writer starts a new part of the log file at this size. The chain continues across the parts
    private static final long MAX_FILE_BYTES = 50L * 1024 * 1024;
    private static final Object POISON = new Object();

    private final Logger log;
    private final LogTarget target;
    private final BlockingQueue<Object> queue = new LinkedBlockingQueue<>();
    private final Thread thread;

    private FileChannel channel;
    private Writer writer;
    private long lastForceMs;
    private long bytesWritten;
    private int partIndex = 1;

    private long seq;
    private String head;
    private final AtomicLong submittedJobs = new AtomicLong();
    // ++ on a volatile isn't atomic. Only the writer thread increments this field, so no update is lost
    private volatile long finishedJobs;
    private volatile long publishedSeq;
    private volatile String publishedHead;
    private volatile boolean dead;

    public LogWriter(Logger log, LogTarget target, ChainStart start) {
        this.log = log;
        this.target = target;
        this.seq = start.seq();
        this.head = start.prevHash();
        this.publishedSeq = start.seq();
        this.publishedHead = start.prevHash();
        this.thread = new Thread(this, "SpeedrunAudit-Writer");
        this.thread.setDaemon(true);
        // A snapshot supplier builds JSON from the data of other mods, so any unchecked exception or Error, such as
        // OutOfMemoryError on a large tree, can end the thread. Mark the writer dead, so the reply to /audit status
        // has the stop.
        this.thread.setUncaughtExceptionHandler((deadThread, failure) -> markDead(failure));
    }

    public void start() throws IOException {
        open(target.logFile());
        thread.start();
    }

    private void open(Path logFile) throws IOException {
        // APPEND: opening an existing log file never truncates the chain evidence of the file
        channel = FileChannel
            .open(logFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
        bytesWritten = channel.size();
        writer = new OutputStreamWriter(Channels.newOutputStream(channel), StandardCharsets.UTF_8);
    }

    public void submit(PendingEvent event) {
        if (!dead) {
            submittedJobs.incrementAndGet();
            queue.add(event);
        }
    }

    public void submitSnapshot(SnapshotJob job) {
        if (!dead) {
            submittedJobs.incrementAndGet();
            queue.add(job);
        }
    }

    /** The seq of the last line written to the file. {@link #publishedHead()} returns the hash of that line. */
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

    /**
     * Wait until the writer has finished every job submitted before the call, the writer is dead, or {@code timeoutMs}
     * passes. Return whether every such job is finished. After a true return, {@link #publishedSeq()} includes the last
     * of those jobs.
     */
    public boolean drain(long timeoutMs) {
        final long POLL_MS = 20;
        final long target = submittedJobs.get();
        final long deadline = System.currentTimeMillis() + timeoutMs;
        while (finishedJobs < target && !dead && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(POLL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread()
                    .interrupt();
                return false;
            }
        }
        return finishedJobs >= target;
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

    /** Take jobs until the poison job arrives, then close the file. */
    @Override
    public void run() {
        try {
            while (true) {
                final Object job = queue.poll(1, TimeUnit.SECONDS);
                if (job == POISON) {
                    break;
                }
                if (job instanceof PendingEvent event) {
                    appendLine(event);
                    finishedJobs++;
                } else if (job instanceof SnapshotJob snapshot) {
                    writeSnapshot(snapshot);
                    finishedJobs++;
                } else if (job == null) {
                    maybeForce(false);
                }
            }
            finish();
        } catch (IOException | InterruptedException failure) {
            // a full disk or a closed stream stops the writer. The game keeps running
            markDead(failure);
        }
    }

    private void markDead(Throwable failure) {
        dead = true;
        log.error("Audit writer died — logging has STOPPED", failure);
    }

    /**
     * Append {@code event} as the next line of the chain. The bytes of the line feed the hash chain, so NEVER reorder
     * or rename a field.
     */
    private void appendLine(PendingEvent event) throws IOException {
        final JsonObject line = new JsonObject();
        line.addProperty("v", 1);
        line.addProperty("seq", seq);
        line.addProperty("sid", target.sessionId());
        line.addProperty("sidx", target.sessionIndex());
        line.addProperty("t", event.type());
        line.addProperty(
            "wall",
            event.clocks()
                .wallMs());
        line.addProperty(
            "ticks",
            event.clocks()
                .ticks());
        line.addProperty(
            "pticks",
            event.clocks()
                .onlineTicks());
        line.addProperty("thread", event.threadName());
        line.add("data", event.data());
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

        maybeForce(isUrgent(event.type()));
        rollIfNeeded();
    }

    private static boolean isUrgent(String type) {
        return type.startsWith("session_") || type.equals("command")
            || type.equals("nei_cheat")
            || type.equals("gamemode_change")
            || type.equals("gamerule_change")
            || type.equals("gt_global_energy");
    }

    private void maybeForce(boolean urgent) throws IOException {
        final long now = System.currentTimeMillis();
        if (urgent || now - lastForceMs > FORCE_INTERVAL_MS) {
            writer.flush();
            channel.force(false);
            lastForceMs = now;
        }
    }

    // writer thread only
    private void rollIfNeeded() throws IOException {
        if (bytesWritten < MAX_FILE_BYTES) {
            return;
        }
        finish();
        partIndex++;
        final String fileName = target.logFile()
            .getFileName()
            .toString();
        final String base = fileName.substring(0, fileName.length() - ".jsonl".length());
        open(
            target.logFile()
                .resolveSibling(base + "-p" + partIndex + ".jsonl"));
    }

    private void finish() throws IOException {
        writer.flush();
        channel.force(false);
        writer.close();
    }

    private void writeSnapshot(SnapshotJob snapshot) throws IOException {
        final Path file = target.snapshotDir()
            .resolve(snapshot.fileName());
        final byte[] json = JsonUtil.GSON.toJson(
            snapshot.content()
                .get())
            .getBytes(StandardCharsets.UTF_8);
        try (GZIPOutputStream gz = new GZIPOutputStream(Files.newOutputStream(file))) {
            gz.write(json);
        }
        final byte[] gzBytes = Files.readAllBytes(file);
        final JsonObject refData = snapshot.refData();
        refData.addProperty("file", snapshot.fileName());
        refData.addProperty("fileSha256", JsonUtil.sha256Hex(gzBytes));
        refData.addProperty("rawBytes", json.length);
        appendLine(new PendingEvent(snapshot.type(), refData, snapshot.clocks(), "writer"));
    }

    /** The session a writer writes for, and its files. */
    public record LogTarget(String sessionId, int sessionIndex, Path logFile, Path snapshotDir) {}

    /** The seq of the first line to write, and the hash for the prev field of that line. */
    public record ChainStart(long seq, String prevHash) {}

    /** {@code content} runs on the writer thread. */
    public record SnapshotJob(String type, String fileName, Supplier<JsonElement> content, JsonObject refData,
        TickClock.Reading clocks) {}
}
