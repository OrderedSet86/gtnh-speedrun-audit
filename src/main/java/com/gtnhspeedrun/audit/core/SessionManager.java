package com.gtnhspeedrun.audit.core;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;

import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.StatCollector;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.world.WorldEvent;

import org.apache.logging.log4j.Logger;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.gtnhspeedrun.audit.command.CommandAudit;
import com.gtnhspeedrun.audit.compat.BackupAccess;
import com.gtnhspeedrun.audit.export.AuditExport;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.ModContainer;
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
    private final Map<UUID, double[]> preTimingPositions = new HashMap<>();
    private final Set<String> onceKeys = new HashSet<>();
    private final List<Runnable> snapshotHooks = new ArrayList<>();

    private AuditDirs dirs;
    private ChainAnchorData anchor;
    private TickClock clock;
    private LogWriter writer;
    private AuditLogger logger;
    private String sessionId;
    private int sessionIndex;
    private long sessionStartTicks;
    private long sessionStartOnlineTicks;
    private String verifyVerdict = "UNKNOWN";
    private boolean savingWasOff;
    private Boolean backupWasRunning;
    // null until the first overworld save of this session
    private AnchorValues lastSavedAnchor;

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

        final Tail tail = findTail();
        // the log of a clean stop ends with session_end
        final boolean previousCrashed = tail.seq() >= 0 && !"session_end".equals(tail.type());
        verifyVerdict = classify(tail, previousCrashed);

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

        anchor.cleanShutdown = false;
        anchor.sessionId = sessionId;
        anchor.markDirty();
        // Autosave runs every 900 ticks. Save the anchor now, so that after a crash at any point the saved anchor has
        // this session's id. classify() needs that id to tell a crash from a restore.
        if (!overworld.levelSaving) {
            overworld.mapStorage.saveAllData();
        }

        logger.log("session_start", buildSessionStartData(overworld, tail, previousCrashed));
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

    public void stopping() {
        final long DRAIN_TIMEOUT_MS = 15_000;
        if (logger == null) {
            return;
        }
        final JsonObject data = new JsonObject();
        data.addProperty("uptimeTicks", clock.get() - sessionStartTicks);
        data.addProperty("onlineUptimeTicks", clock.getOnline() - sessionStartOnlineTicks);
        data.addProperty("reason", "stop");
        // The shutdown save runs after this event and skips every world in savingOffDims. With dim 0 in
        // savingOffDims the anchor is not saved, and the next start is classified as WORLD_ROLLBACK.
        WorldSaving.addSavingFields(server, data);
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
    }

    // ------------------------------------------------------------------ chain resume

    /**
     * Return the line with the highest seq over every session file, rollback-suffixed files included. The log
     * directory is the source of truth of the chain, and the chain resumes from the exact bytes of that line.
     */
    private Tail findTail() throws IOException {
        int maxIndex = 0;
        long seq = -1;
        String hash = "";
        String sid = "";
        String type = "";
        for (Path file : AuditDirs.listEntriesOrEmpty(dirs.log())) {
            final String fileName = file.getFileName()
                .toString();
            final Matcher name = Verifier.CHAIN_FILE_NAME.matcher(fileName);
            if (!name.matches()) {
                continue;
            }
            maxIndex = Math.max(maxIndex, Integer.parseInt(name.group(1)));
            final byte[] lastLine = readLastLine(file);
            if (lastLine == null) {
                continue;
            }
            try {
                final JsonObject obj = new JsonParser().parse(new String(lastLine, StandardCharsets.UTF_8))
                    .getAsJsonObject();
                final long lineSeq = obj.get("seq")
                    .getAsLong();
                if (lineSeq > seq) {
                    seq = lineSeq;
                    hash = JsonUtil.sha256Hex(lastLine);
                    sid = obj.get("sid")
                        .getAsString();
                    type = obj.get("t")
                        .getAsString();
                }
            } catch (RuntimeException e) {
                log.warn("Unparseable tail line in {} — verifier will flag it", fileName);
            }
        }
        return new Tail(maxIndex, seq, hash, sid, type);
    }

    /**
     * Return the bytes of the last non-empty line of {@code file}, with trailing CR and LF bytes skipped, or null for a
     * file with no such line.
     */
    private static byte[] readLastLine(Path file) throws IOException {
        try (RandomAccessFile access = new RandomAccessFile(file.toFile(), "r")) {
            long end = access.length();
            while (end > 0) {
                access.seek(end - 1);
                final int lastByte = access.read();
                if (lastByte == '\n' || lastByte == '\r') {
                    end--;
                } else {
                    break;
                }
            }
            if (end == 0) {
                return null;
            }
            long start = end;
            while (start > 0) {
                access.seek(start - 1);
                if (access.read() == '\n') {
                    break;
                }
                start--;
            }
            final byte[] bytes = new byte[(int) (end - start)];
            access.seek(start);
            access.readFully(bytes);
            return bytes;
        }
    }

    /**
     * Return the startup verdict for session_start from the log tail and the anchor alone. {@link Verifier} walks
     * every line.
     */
    private String classify(Tail tail, boolean previousCrashed) {
        if (tail.seq() < 0) {
            return anchor.lastSeq < 0 ? "NEW_WORLD" : "LOG_MISSING";
        }
        if (anchor.lastSeq < tail.seq()) {
            // The world is behind the log. The gap is a crash only if the log has no clean stop, and the world was
            // last saved mid-session by the session that crashed. start() saves the anchor at once. A restore of a
            // save from any earlier session, or of a save made at a clean stop, fails this check.
            final boolean crashedSessionSavedLast = !anchor.cleanShutdown && !anchor.sessionId.isEmpty()
                && anchor.sessionId.equals(tail.sid());
            if (previousCrashed && crashedSessionSavedLast) {
                return "CRASH_RECOVERY";
            }
            return anchor.lastSeq < 0 ? "ANCHOR_MISSING" : "WORLD_ROLLBACK";
        }
        if (anchor.lastSeq > tail.seq()) {
            return "LOG_TRUNCATED";
        }
        return anchor.lastHash.equals(tail.hash()) ? "OK" : "TAMPERED_TAIL";
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

    // ------------------------------------------------------------------ session_start payload

    /** Build the data of the session_start line. The run analyzer reads these keys, so NEVER rename one. */
    private JsonObject buildSessionStartData(WorldServer overworld, Tail tail, boolean previousCrashed) {
        final JsonObject data = new JsonObject();
        data.addProperty("worldAuditUuid", anchor.worldAuditUuid);
        data.addProperty("verifyVerdict", verifyVerdict);
        data.addProperty("timingStarted", anchor.timingStarted);
        data.addProperty("timingStartWallMs", anchor.timingStartWallMs);
        data.addProperty("onlineTicks", anchor.cumulativeOnlineTicks);
        data.addProperty("previousCrashed", previousCrashed);
        data.addProperty("dedicated", server.isDedicatedServer());
        data.addProperty("worldFolder", server.getFolderName());
        data.addProperty("seed", overworld.getSeed());
        data.addProperty("difficulty", String.valueOf(overworld.difficultySetting));
        // Vanilla counts every world tick in worldTotalTime, and /time set does not change the count. For a world
        // adopted mid-run, worldTotalTime is the pre-audit IGT baseline. After the mod adopts the world,
        // worldTotalTime advances with the audit clock, so a difference between the two clocks is a cross-check.
        data.addProperty("worldTotalTime", overworld.getTotalWorldTime());
        data.addProperty("mcVersion", server.getMinecraftVersion());
        data.addProperty("javaVersion", System.getProperty("java.version"));
        data.addProperty(
            "modCount",
            Loader.instance()
                .getActiveModList()
                .size());

        data.add(
            "anchor",
            AnchorValues.readFrom(anchor)
                .toJson());

        final JsonObject tailObj = new JsonObject();
        tailObj.addProperty("seq", tail.seq());
        tailObj.addProperty("hash", tail.hash());
        data.add("logTail", tailObj);

        if ("WORLD_ROLLBACK".equals(verifyVerdict) || "CRASH_RECOVERY".equals(verifyVerdict)) {
            final JsonObject rollback = new JsonObject();
            rollback.addProperty("eventsRewound", tail.seq() - anchor.lastSeq);
            rollback.addProperty("wallGapMs", System.currentTimeMillis() - anchor.lastWallMs);
            data.add("rollback", rollback);
        }

        final JsonArray ops = new JsonArray();
        for (String op : server.getConfigurationManager()
            .func_152606_n()) {
            ops.add(JsonUtil.GSON.toJsonTree(op));
        }
        data.add("ops", ops);

        final JsonObject gamerules = new JsonObject();
        for (String rule : overworld.getGameRules()
            .getRules()) {
            gamerules.addProperty(
                rule,
                overworld.getGameRules()
                    .getGameRuleStringValue(rule));
        }
        data.add("gamerules", gamerules);

        final JsonArray mods = new JsonArray();
        for (ModContainer mod : Loader.instance()
            .getActiveModList()) {
            mods.add(JsonUtil.GSON.toJsonTree(mod.getModId() + "@" + mod.getVersion()));
        }
        data.add("mods", mods);
        return data;
    }

    // ------------------------------------------------------------------ ticking

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (!(event.phase == TickEvent.Phase.END && anchor != null)) {
            return;
        }
        checkWorldSaving();
        if (!anchor.timingStarted) {
            // IGT convention: the run clocks don't advance until the first player movement, so worldgen lag, spawn
            // staring and menu time don't count. Every line still gets a wall timestamp, and RTA counts from the
            // same first movement.
            checkFirstMovement();
            return;
        }
        final long ticks = clock.increment(!server.getConfigurationManager().playerEntityList.isEmpty());
        if (ticks % ANCHOR_EVERY_TICKS == 0) {
            publishAnchor(false);
        }
    }

    /**
     * Log world_saving when overworld saving turns off, as at the start of a backup, and when overworld saving turns
     * back on. Log backup_done when a ServerUtilities backup thread finishes.
     *
     * <p>
     * After the first overworld save of the session, the off line has {@code lastSave}: the anchor values that the
     * latest overworld save wrote. A backup saves every world and then turns saving off, so these values are the
     * anchor inside the zip of that backup. A restore of that backup starts with session_start.anchor equal to
     * lastSave, so a reader can tell which backup came back.
     */
    private void checkWorldSaving() {
        final boolean off = WorldSaving.isOverworldSavingOff();
        if (off != savingWasOff) {
            savingWasOff = off;
            final JsonObject data = new JsonObject();
            data.addProperty("enabled", !off);
            WorldSaving.addSavingFields(server, data);
            if (off && lastSavedAnchor != null) {
                data.add("lastSave", lastSavedAnchor.toJson());
            }
            logger.log("world_saving", data);
        }
        final Boolean running = BackupAccess.isBackupRunning();
        if (Boolean.TRUE.equals(backupWasRunning) && Boolean.FALSE.equals(running)) {
            final JsonObject data = new JsonObject();
            BackupAccess.describeNewest(data);
            logger.log("backup_done", data);
        }
        backupWasRunning = running;
    }

    /**
     * Start timing at the first horizontal movement of any player. The server receives no key presses, so horizontal
     * movement stands in for the first WASD press. One tick of walking moves about 0.21 blocks. A threshold of 0.03
     * blocks is above server jitter and below a single step. Vertical movement does not count, so a drop from a spawn
     * platform does not start the timer.
     */
    private void checkFirstMovement() {
        // 0.03 blocks, squared
        final double MIN_STEP_SQUARED = 0.0009;
        for (Object entry : server.getConfigurationManager().playerEntityList) {
            final EntityPlayerMP player = (EntityPlayerMP) entry;
            final UUID id = player.getGameProfile()
                .getId();
            final double[] prev = preTimingPositions.get(id);
            if (prev == null) {
                preTimingPositions.put(id, new double[] { player.posX, player.posZ });
                continue;
            }
            final double dx = player.posX - prev[0];
            final double dz = player.posZ - prev[1];
            prev[0] = player.posX;
            prev[1] = player.posZ;
            if (dx * dx + dz * dz > MIN_STEP_SQUARED) {
                anchor.timingStarted = true;
                anchor.timingStartWallMs = System.currentTimeMillis();
                preTimingPositions.clear();
                final JsonObject data = new JsonObject();
                data.addProperty("uuid", id.toString());
                data.addProperty("name", player.getCommandSenderName());
                data.addProperty("dim", player.dimension);
                logger.log("timing_started", data);
                publishAnchor(false);
                return;
            }
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
     * Record the anchor values that the latest overworld save wrote, for lastSave on the world_saving line. Forge
     * posts WorldEvent.Save after saveLevel() wrote the map storage of the overworld, the anchor included.
     * saveLevel() and the post are in one save call on the server thread, so publishAnchor() can't run between them.
     * The anchor fields read here are the values that the save wrote.
     */
    @SubscribeEvent
    public void onWorldSave(WorldEvent.Save event) {
        if (!(anchor != null && !event.world.isRemote && event.world.provider.dimensionId == 0)) {
            return;
        }
        lastSavedAnchor = AnchorValues.readFrom(anchor);
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

    public void snapshotNow(ICommandSender sender) {
        if (snapshotHooks.isEmpty()) {
            CommandAudit.reply(sender, "gtnhspeedrunaudit.command.snapshot.none");
            return;
        }
        for (Runnable hook : snapshotHooks) {
            hook.run();
        }
        CommandAudit.reply(sender, "gtnhspeedrunaudit.command.snapshot.queued", snapshotHooks.size());
    }

    /**
     * Zip every log and snapshot, and verify the whole chain again, on a background thread. The background thread
     * sends {@code sender} the path of the zip, or the failure.
     */
    public void export(ICommandSender sender) {
        final long DRAIN_TIMEOUT_MS = 5_000;
        writer.drain(DRAIN_TIMEOUT_MS);
        final AuditExport bundle = new AuditExport(
            dirs,
            new AuditExport.AnchorView(
                anchor.worldAuditUuid,
                writer.publishedSeq(),
                clock.get(),
                clock.getOnline(),
                anchor.timingStartWallMs));
        CommandAudit
            .reply(sender, "gtnhspeedrunaudit.command.export.building", formatMegabytes(directorySize(dirs.log())));
        // a host's web panel lists files by their path under the server folder, not by the absolute path in the
        // container
        final boolean dedicated = server.isDedicatedServer();
        final String rootName = StatCollector.translateToLocal(
            dedicated ? "gtnhspeedrunaudit.command.export.folder.server"
                : "gtnhspeedrunaudit.command.export.folder.instance");
        final String landmarkKey = dedicated ? "gtnhspeedrunaudit.command.export.landmark.server"
            : "gtnhspeedrunaudit.command.export.landmark.instance";
        // stopped() sets the anchor field to null, and the server can stop while the zip is written
        final String worldAuditUuid = anchor.worldAuditUuid;
        final Thread exportThread = new Thread(() -> {
            try {
                final Path zip = bundle.writeZip();
                CommandAudit.reply(sender, "gtnhspeedrunaudit.command.export.ready", formatMegabytes(Files.size(zip)));
                CommandAudit.reply(
                    sender,
                    "gtnhspeedrunaudit.command.export.path",
                    rootName,
                    worldAuditUuid,
                    zip.getFileName());
                CommandAudit.reply(sender, landmarkKey, rootName);
            } catch (IOException | RuntimeException e) {
                CommandAudit.reply(sender, "gtnhspeedrunaudit.command.export.failed", e);
            }
        }, "SpeedrunAudit-Export");
        exportThread.setDaemon(true);
        exportThread.start();
    }

    private static long directorySize(Path dir) {
        long bytes = 0;
        for (Path entry : AuditDirs.listEntriesOrEmpty(dir)) {
            try {
                bytes += Files.size(entry);
            } catch (IOException e) {
                // the size is an estimate for a chat line, so an unreadable entry counts as 0 bytes
            }
        }
        return bytes;
    }

    private static String formatMegabytes(long bytes) {
        final long HALF_MEGABYTE = 500_000;
        final double MEGABYTE = 1_000_000.0;
        return bytes < HALF_MEGABYTE ? "<1" : String.valueOf(Math.round(bytes / MEGABYTE));
    }

    // ------------------------------------------------------------------ accessors

    public AuditLogger logger() {
        return logger;
    }

    public ChainAnchorData anchor() {
        return anchor;
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

    /** The line with the highest seq in the log, and the highest session index of any log file name. */
    private record Tail(int maxIndex, long seq, String hash, String sid, String type) {}

    private record AnchorValues(long seq, long ticks, long wallMs) {

        private static AnchorValues readFrom(ChainAnchorData anchor) {
            return new AnchorValues(anchor.lastSeq, anchor.cumulativeTicks, anchor.lastWallMs);
        }

        private JsonObject toJson() {
            final JsonObject values = new JsonObject();
            values.addProperty("seq", seq);
            values.addProperty("ticks", ticks);
            values.addProperty("wall", wallMs);
            return values;
        }
    }
}
