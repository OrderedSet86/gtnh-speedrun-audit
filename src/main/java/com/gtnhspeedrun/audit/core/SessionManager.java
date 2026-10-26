package com.gtnhspeedrun.audit.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nullable;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.MinecraftForge;

import org.apache.logging.log4j.Logger;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.gtnhspeedrun.audit.api.AuditEvents;
import com.gtnhspeedrun.audit.core.SessionStartData.ForkStart;

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
 *
 * <p>
 * A world started in a folder other than {@link ChainAnchorData#homeFolder} is a copy. The copy gets a new audit UUID
 * and a new chain, linked to the copied save of the parent chain, so play in a copy never enters the parent's chain.
 */
public final class SessionManager {

    // a world save writes an anchor at most this many ticks behind the log
    private static final int ANCHOR_EVERY_TICKS = 100;
    private static final int REPORT_ATTEMPTS_EVERY_TICKS = 20;
    private static final Duration ATTEMPT_WINDOW = Duration.ofMinutes(1);

    private final Logger log;
    private final MinecraftServer server;
    private final ClientThreadGate gate;
    private final Set<String> onceKeys = new HashSet<>();
    private final List<Runnable> snapshotHooks = new ArrayList<>();
    private final List<Runnable> tickHooks = new ArrayList<>();

    private AuditDirs dirs;
    private ChainAnchorData anchor;
    private TickClock clock;
    private LogWriter writer;
    private AuditLogger logger;
    private AttemptLimiter attempts;
    private TimingStart timingStart;
    private String sessionId;
    private int sessionIndex;
    private long sessionStartTicks;
    private long sessionStartOnlineTicks;
    private String verifyVerdict = "UNKNOWN";
    private boolean stopping;
    private Thread exitDrain;

    public SessionManager(Logger log, MinecraftServer server, ClientThreadGate gate) {
        this.log = log;
        this.server = server;
        this.gate = gate;
    }

    // ------------------------------------------------------------------ lifecycle

    /**
     * Resume the chain from the log tail, classify the startup, start the writer and log session_start. The anchor
     * gets this session's id, and the anchor is saved at once unless overworld saving is off.
     */
    public void start() throws IOException {
        final WorldServer overworld = server.worldServers[0];
        anchor = ChainAnchorData.loadOrCreate(overworld);
        final Path auditRoot = server.getFile("speedrun-audit")
            .toPath();
        final String folder = server.getFolderName();
        final boolean homeFolderSet = anchor.homeFolder.isEmpty();
        if (homeFolderSet) {
            anchor.homeFolder = folder;
        }
        final ForkLink fork = anchor.homeFolder.equals(folder) ? null : forkIdentity(folder);

        dirs = AuditDirs.under(auditRoot.resolve(anchor.worldAuditUuid));
        Files.createDirectories(dirs.log());
        try {
            Files.createDirectories(dirs.snapshots());
        } catch (IOException e) {
            // the session logs events until the first snapshot write fails and stops the writer
        }

        final ChainTail tail = ChainTail.read(dirs.log(), log);
        // the log of a clean stop ends with session_end
        final boolean previousCrashed = tail.seq() >= 0 && !"session_end".equals(tail.type());
        // a copy's new chain has no log yet, and classify() would read the start as NEW_WORLD
        verifyVerdict = fork != null ? "WORLD_COPY"
            : StartupVerdict.classify(tail, previousCrashed, StartupVerdict.SavedAnchor.readFrom(anchor));
        // an ANCHOR_MISSING start can be a restore of a save from before timing started, with seq -1 in the anchor
        final boolean isRestore = StartupVerdict.isWorldRewound(verifyVerdict)
            || "ANCHOR_MISSING".equals(verifyVerdict);
        final LoadedSave loadedSave = isRestore ? lookUpLoadedSave(dirs.log(), AnchorValues.readFrom(anchor)) : null;

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
        registerExitDrain(writer);
        logger = new AuditLogger(writer, clock, gate);
        attempts = new AttemptLimiter(logger, ATTEMPT_WINDOW);
        AuditEvents.attachSession(clock::get, log);
        // No event of the session is logged yet. Each listener with history gets the history first
        replayHistory(tail, auditRoot, fork);
        timingStart = new TimingStart(server, anchor, logger);

        anchor.cleanShutdown = false;
        anchor.sessionId = sessionId;
        anchor.markDirty();
        // Autosave runs every 900 ticks. Save the anchor now, so that after a crash at any point the saved anchor has
        // this session's id. StartupVerdict.classify() needs that id to tell a crash from a restore.
        if (!overworld.levelSaving) {
            overworld.mapStorage.saveAllData();
        }

        final SessionStartData startData = new SessionStartData(
            server,
            anchor,
            tail,
            verifyVerdict,
            previousCrashed,
            readForkStart(auditRoot, fork),
            loadedSave,
            ChainFamily.listCopies(auditRoot, anchor.worldAuditUuid, log),
            homeFolderSet);
        logger.log("session_start", startData.toJson());
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

    /**
     * Write the queued lines when the JVM exits without a server stop, as on SIGTERM or {@code System.exit()}. Lines
     * submitted after a large snapshot job wait in the queue for seconds. The writer thread is a daemon, and the JVM
     * ends a daemon at exit.
     */
    private void registerExitDrain(LogWriter sessionWriter) {
        final long EXIT_DRAIN_MS = 2_000;
        exitDrain = new Thread(() -> sessionWriter.drain(EXIT_DRAIN_MS), "SpeedrunAudit-ExitDrain");
        Runtime.getRuntime()
            .addShutdownHook(exitDrain);
    }

    /**
     * Give this world a new audit UUID and chain, linked to the copied save, and return the link. The clocks, the
     * timing start and the milestone dedupe sets stay: they are the copied world's.
     */
    private ForkLink forkIdentity(String folder) {
        final ForkLink fork = new ForkLink(
            anchor.worldAuditUuid,
            anchor.homeFolder,
            AnchorValues.readFrom(anchor),
            anchor.lastHash);
        anchor.worldAuditUuid = UUID.randomUUID()
            .toString();
        anchor.homeFolder = folder;
        anchor.lastSeq = -1;
        anchor.lastHash = "";
        anchor.markDirty();
        log.info("world.forked parent={} child={} folder={}", fork.parentUuid(), anchor.worldAuditUuid, folder);
        return fork;
    }

    /** Return the save that {@code loaded} names in the log of {@code logDir}, or null when the log can't be read. */
    @Nullable
    private LoadedSave lookUpLoadedSave(Path logDir, AnchorValues loaded) {
        final long startNanos = System.nanoTime();
        final LoadedSave loadedSave;
        try {
            loadedSave = LoadedSaveLookup.findLoadedSave(logDir, loaded);
        } catch (IOException exception) {
            log.warn("loaded_save.unreadable reason={}", exception.getMessage(), exception);
            return null;
        }
        log.info(
            "loaded_save.lookup kind={} matches={} ms={}",
            loadedSave.kind(),
            loadedSave.matches(),
            TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos));
        return loadedSave;
    }

    /**
     * Send the events of the earlier sessions to the API listeners with history. A world copy gets the events of each
     * ancestor chain up to the copied save first. The last line of a world saved before the end of the log is
     * {@link ChainAnchorData#lastSeq}. A log that can't be read is logged, and the session runs on.
     */
    private void replayHistory(ChainTail tail, Path auditRoot, @Nullable ForkLink fork) {
        final Set<String> historyTypes = AuditEvents.readHistoryTypes();
        if (historyTypes.isEmpty()) {
            return;
        }
        final long lastWorldSeq = StartupVerdict.isWorldRewound(verifyVerdict) ? anchor.lastSeq : tail.seq();
        final long startNanos = System.nanoTime();
        final List<LedgerHistory.History> histories = new ArrayList<>();
        try {
            // from the second session of a copy on, the first line of the chain has the fork link
            final ForkLink ownFork = fork != null ? fork : ChainFamily.readForkLink(dirs.log());
            final List<ForkLink> ancestors = ownFork == null ? List.of()
                : ChainFamily.listAncestors(auditRoot, ownFork);
            for (ForkLink ancestor : ancestors) {
                final Path ancestorLog = AuditDirs.under(auditRoot.resolve(ancestor.parentUuid()))
                    .log();
                histories.add(
                    LedgerHistory.read(
                        ancestorLog,
                        historyTypes,
                        ancestor.parentSave()
                            .seq()));
            }
            histories.add(LedgerHistory.read(dirs.log(), historyTypes, lastWorldSeq));
        } catch (IOException exception) {
            log.warn("history.unreadable reason={}", exception.getMessage(), exception);
            return;
        }
        int eventCount = 0;
        int fileCount = 0;
        int skippedLines = 0;
        for (LedgerHistory.History history : histories) {
            for (LedgerHistory.Event event : history.events()) {
                AuditEvents.publishHistory(event.type(), event.data(), event.igtTicks());
            }
            eventCount += history.events()
                .size();
            fileCount += history.fileCount();
            skippedLines += history.skippedLines();
        }
        log.info(
            "history.replayed events={} files={} skipped={} chains={} ms={}",
            eventCount,
            fileCount,
            skippedLines,
            histories.size(),
            TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos));
    }

    /** Return the fork block for a copy's first session_start, or null when {@code fork} is null. */
    @Nullable
    private ForkStart readForkStart(Path auditRoot, @Nullable ForkLink fork) {
        if (fork == null) {
            return null;
        }
        final Path parentLog = AuditDirs.under(auditRoot.resolve(fork.parentUuid()))
            .log();
        final boolean parentLogPresent = Files.isDirectory(parentLog);
        return new ForkStart(
            fork,
            parentLogPresent,
            parentLogPresent ? lookUpLoadedSave(parentLog, fork.parentSave()) : null);
    }

    /** Log session_end with {@code savingFields} after the reason key, in their order, and publish a clean anchor. */
    public void stopping(JsonObject savingFields) {
        final long DRAIN_TIMEOUT_MS = 15_000;
        if (logger == null) {
            return;
        }
        stopping = true;
        attempts.reportAllWindows();
        final JsonObject data = new JsonObject();
        data.addProperty("uptimeTicks", clock.get() - sessionStartTicks);
        data.addProperty("onlineUptimeTicks", clock.getOnline() - sessionStartOnlineTicks);
        data.addProperty("reason", "stop");
        for (Map.Entry<String, JsonElement> field : savingFields.entrySet()) {
            data.add(field.getKey(), field.getValue());
        }
        if (logger.countDroppedLines() > 0) {
            data.addProperty("clientThreadLinesDropped", logger.countDroppedLines());
        }
        logger.log("session_end", data);
        writer.drain(DRAIN_TIMEOUT_MS);
        // The world save after FMLServerStoppingEvent writes this anchor. After a clean stop the anchor has the seq
        // and hash of the session_end line, unless drain() timed out or the writer is dead.
        publishAnchor(true);
    }

    /**
     * Unregister the session's handlers, close the writer, and drop every reference to the session's world. Call after
     * a stop, or after a {@link #start} that failed partway.
     */
    public void stopped() {
        final long CLOSE_TIMEOUT_MS = 5_000;
        FMLCommonHandler.instance()
            .bus()
            .unregister(this);
        MinecraftForge.EVENT_BUS.unregister(this);
        if (exitDrain != null) {
            try {
                Runtime.getRuntime()
                    .removeShutdownHook(exitDrain);
            } catch (IllegalStateException exception) {
                // the JVM is exiting, and exitDrain runs
            }
            exitDrain = null;
        }
        if (writer != null) {
            writer.close(CLOSE_TIMEOUT_MS);
        }
        AuditEvents.attachSession(null, log);
        writer = null;
        logger = null;
        attempts = null;
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

    /**
     * At the end of each tick: run the tick hooks, report ended attempt windows once a second, then start timing or
     * advance the run clocks and publish the anchor every {@code ANCHOR_EVERY_TICKS}.
     */
    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (!(event.phase == TickEvent.Phase.END && anchor != null)) {
            return;
        }
        for (Runnable hook : tickHooks) {
            hook.run();
        }
        if (server.getTickCounter() % REPORT_ATTEMPTS_EVERY_TICKS == 0) {
            attempts.reportEndedWindows();
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

    /**
     * Publish the anchor with every line logged so far, so the save that follows writes an anchor whose lastSeq covers
     * every milestone flag of the anchor. Call on the server thread at the start of an overworld save. After the
     * timeout, the anchor is published with the lines written so far.
     */
    public void syncAnchorBeforeSave() {
        final long DRAIN_TIMEOUT_MS = 1_000;
        if (anchor == null) {
            return;
        }
        if (!writer.drain(DRAIN_TIMEOUT_MS)) {
            log.warn("anchor.sync_timeout queued={}", writer.queueDepth());
        }
        publishAnchor(anchor.cleanShutdown);
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

    public AttemptLimiter attempts() {
        return attempts;
    }

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

    /** Return whether {@link #stopping} has run. No line may follow session_end. */
    public boolean isStopping() {
        return stopping;
    }

    public MinecraftServer server() {
        return server;
    }
}
