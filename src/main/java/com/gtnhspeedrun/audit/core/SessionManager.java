package com.gtnhspeedrun.audit.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.MinecraftForge;

import org.apache.logging.log4j.Logger;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/**
 * Run the audit for one server run, a session: the writer, the run clocks and the anchor. In singleplayer one JVM
 * opens many worlds, so no field here may outlive the session's world. {@link #stopped()} drops every reference at
 * FMLServerStoppedEvent.
 *
 * <p>
 * The logs are outside the world folder, in one directory per audit UUID. The rules allow a backup restore after a
 * base explodes. A restore rewinds the world and the world's anchor, but never the log. {@link #start()} classifies
 * the gap between the log and the anchor as WORLD_ROLLBACK, and the rewound events stay in the log.
 */
public final class SessionManager {

    // a world save writes an anchor at most this many ticks behind the log
    private static final int ANCHOR_EVERY_TICKS = 100;

    private final Logger log;
    private final MinecraftServer server;
    private final Set<String> onceKeys = new HashSet<>();
    private final List<Runnable> snapshotHooks = new ArrayList<>();
    private final List<Runnable> tickHooks = new ArrayList<>();

    private AuditDirs dirs;
    private ChainAnchorData anchor;
    private TickClock clock;
    private LogWriter writer;
    private AuditLogger logger;
    private TimingStart timingStart;
    private String sessionId;
    private int sessionIndex;
    private long sessionStartTicks;
    private long sessionStartOnlineTicks;
    private String verifyVerdict = "UNKNOWN";

    public SessionManager(Logger log, MinecraftServer server) {
        this.log = log;
        this.server = server;
    }

    // ------------------------------------------------------------------ lifecycle

    /**
     * Resume the chain from the log tail, classify the startup, start the writer and log session_start. The anchor
     * gets this session's id, and the anchor is saved at once unless overworld saving is off.
     */
    public void start() throws IOException {
        final WorldServer overworld = server.worldServers[0];
        anchor = ChainAnchorData.loadOrCreate(overworld);

        dirs = AuditDirs.under(
            server.getFile("speedrun-audit")
                .toPath()
                .resolve(anchor.worldAuditUuid));
        Files.createDirectories(dirs.log());
        try {
            Files.createDirectories(dirs.snapshots());
        } catch (IOException e) {
            // the session logs events until the first snapshot write fails and stops the writer
        }

        final ChainTail tail = ChainTail.read(dirs.log(), log);
        // the log of a clean stop ends with session_end
        final boolean previousCrashed = tail.seq() >= 0 && !"session_end".equals(tail.type());
        verifyVerdict = StartupVerdict.classify(tail, previousCrashed, StartupVerdict.SavedAnchor.readFrom(anchor));

        sessionId = UUID.randomUUID()
            .toString();
        sessionIndex = tail.maxIndex() + 1;
        final Path logFile = newLogFile();

        clock = new TickClock(anchor.cumulativeTicks, anchor.cumulativeOnlineTicks);
        sessionStartTicks = anchor.cumulativeTicks;
        sessionStartOnlineTicks = anchor.cumulativeOnlineTicks;
        final String head = tail.seq() < 0 ? Verifier.genesisHash(anchor.worldAuditUuid) : tail.hash();
        writer = new LogWriter(
            log,
            new LogWriter.LogTarget(sessionId, sessionIndex, logFile, dirs.snapshots()),
            new LogWriter.ChainStart(tail.seq() + 1, head));
        writer.start();
        logger = new AuditLogger(writer, clock);
        timingStart = new TimingStart(server, anchor, logger);

        anchor.cleanShutdown = false;
        anchor.sessionId = sessionId;
        anchor.markDirty();
        // Autosave runs every 900 ticks. Save the anchor now, so that after a crash at any point the saved anchor has
        // this session's id. StartupVerdict.classify() needs that id to tell a crash from a restore.
        if (!overworld.levelSaving) {
            overworld.mapStorage.saveAllData();
        }

        logger
            .log("session_start", new SessionStartData(server, anchor, tail, verifyVerdict, previousCrashed).toJson());
        FMLCommonHandler.instance()
            .bus()
            .register(this);
        MinecraftForge.EVENT_BUS.register(this);
        log.info(
            "Speedrun audit session {} started (verdict: {}), logging to {}",
            sessionIndex,
            verifyVerdict,
            logFile);
    }

    /** Log session_end with {@code savingFields} after the reason key, in their order, and publish a clean anchor. */
    public void stopping(JsonObject savingFields) {
        final long DRAIN_TIMEOUT_MS = 15_000;
        if (logger == null) {
            return;
        }
        final JsonObject data = new JsonObject();
        data.addProperty("uptimeTicks", clock.get() - sessionStartTicks);
        data.addProperty("onlineUptimeTicks", clock.getOnline() - sessionStartOnlineTicks);
        data.addProperty("reason", "stop");
        for (Map.Entry<String, JsonElement> field : savingFields.entrySet()) {
            data.add(field.getKey(), field.getValue());
        }
        logger.log("session_end", data);
        writer.drain(DRAIN_TIMEOUT_MS);
        // The world save after FMLServerStoppingEvent writes this anchor. After a clean stop the anchor has the seq
        // and hash of the session_end line, unless drain() timed out or the writer is dead.
        publishAnchor(true);
    }

    public void stopped() {
        final long CLOSE_TIMEOUT_MS = 5_000;
        FMLCommonHandler.instance()
            .bus()
            .unregister(this);
        MinecraftForge.EVENT_BUS.unregister(this);
        if (writer != null) {
            writer.close(CLOSE_TIMEOUT_MS);
        }
        writer = null;
        logger = null;
        anchor = null;
        timingStart = null;
    }

    private Path newLogFile() {
        Path logFile = dirs.log()
            .resolve(String.format("audit-%06d.jsonl", sessionIndex));
        // NEVER overwrite a log file: every log file is chain evidence. A name collision gets an -r suffix
        int collision = 2;
        while (Files.exists(logFile)) {
            logFile = dirs.log()
                .resolve(String.format("audit-%06d-r%d.jsonl", sessionIndex, collision++));
        }
        return logFile;
    }

    // ------------------------------------------------------------------ ticking

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (!(event.phase == TickEvent.Phase.END && anchor != null)) {
            return;
        }
        for (Runnable hook : tickHooks) {
            hook.run();
        }
        if (!anchor.timingStarted) {
            // IGT convention: the run clocks don't advance until the first player movement, so worldgen lag, spawn
            // staring and menu time don't count. Every line still gets a wall timestamp, and RTA counts from the
            // same first movement.
            if (timingStart.checkFirstMovement()) {
                publishAnchor(false);
            }
            return;
        }
        final long ticks = clock.increment(!server.getConfigurationManager().playerEntityList.isEmpty());
        if (ticks % ANCHOR_EVERY_TICKS == 0) {
            publishAnchor(false);
        }
    }

    private void publishAnchor(boolean clean) {
        anchor.lastSeq = writer.publishedSeq();
        anchor.lastHash = writer.publishedHead();
        anchor.cumulativeTicks = clock.get();
        anchor.cumulativeOnlineTicks = clock.getOnline();
        anchor.lastWallMs = System.currentTimeMillis();
        anchor.cleanShutdown = clean;
        anchor.markDirty();
    }

    /**
     * Run {@code hook} first in every end-of-tick of this session, before timing_started can be logged and before the
     * run clocks advance. A hook registered as a separate tick listener would run after this class's handler.
     */
    public void addTickHook(Runnable hook) {
        tickHooks.add(hook);
    }

    // ------------------------------------------------------------------ session-scoped dedupe

    /**
     * Return true on the first call with {@code key} in this session. Mixin sinks also call this method off the server
     * thread.
     */
    public boolean isFirstTime(String key) {
        synchronized (onceKeys) {
            return onceKeys.add(key);
        }
    }

    // ------------------------------------------------------------------ on-demand actions

    public void addSnapshotHook(Runnable hook) {
        snapshotHooks.add(hook);
    }

    /** Run every snapshot hook, and return how many ran. */
    public int runSnapshotHooks() {
        for (Runnable hook : snapshotHooks) {
            hook.run();
        }
        return snapshotHooks.size();
    }

    // ------------------------------------------------------------------ accessors

    public AuditLogger logger() {
        return logger;
    }

    public ChainAnchorData anchor() {
        return anchor;
    }

    public AuditDirs dirs() {
        return dirs;
    }

    public Path auditDir() {
        return dirs.root();
    }

    public Path logDir() {
        return dirs.log();
    }

    public Path snapshotDir() {
        return dirs.snapshots();
    }

    public int sessionIndex() {
        return sessionIndex;
    }

    public String verifyVerdict() {
        return verifyVerdict;
    }

    public MinecraftServer server() {
        return server;
    }
}
