package com.gtnhspeedrun.audit.core;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.WorldServer;

import org.apache.logging.log4j.Logger;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.ModContainer;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/**
 * One instance per server run (a "session"). Owns the writer, the run clock and the anchor, and is torn down
 * completely in FMLServerStoppedEvent — in singleplayer the same JVM opens many worlds, and nothing here may
 * outlive the world it was built for.
 *
 * <p>
 * Logs live OUTSIDE the world folder (per-world by audit UUID) so that a backup restore — an allowed workflow
 * when a base explodes — rewinds the world and its anchor but never the log. The verifier then reads the
 * difference as WORLD_ROLLBACK instead of losing the evidence.
 */
public final class SessionManager {

    private static final Pattern LOG_NAME = Pattern.compile("audit-(\\d{6})(?:-r(\\d+))?(?:-p(\\d+))?\\.jsonl");
    /** Anchor persistence cadence; bounds how far the anchor can lag the log after a crash. */
    private static final int ANCHOR_EVERY_TICKS = 100;

    private final Logger log;
    private final MinecraftServer server;

    private File auditDir;
    private File logDir;
    private File snapshotDir;
    private File dirtyMarker;

    private ChainAnchorData anchor;
    private TickClock clock;
    private LogWriter writer;
    private AuditLogger logger;
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

    public void start() throws IOException {
        final WorldServer overworld = server.worldServers[0];
        anchor = ChainAnchorData.get(overworld);

        auditDir = server.getFile("speedrun-audit/" + anchor.worldAuditUuid);
        logDir = new File(auditDir, "log");
        snapshotDir = new File(auditDir, "snapshots");
        logDir.mkdirs();
        snapshotDir.mkdirs();
        dirtyMarker = new File(auditDir, "session.dirty");

        final boolean previousCrashed = dirtyMarker.exists();
        final Tail tail = findTail();
        verifyVerdict = classify(tail, previousCrashed);

        sessionId = UUID.randomUUID()
            .toString();
        sessionIndex = tail.maxIndex + 1;
        final File logFile = newLogFile();

        clock = new TickClock(anchor.cumulativeTicks, anchor.cumulativeOnlineTicks);
        sessionStartTicks = anchor.cumulativeTicks;
        sessionStartOnlineTicks = anchor.cumulativeOnlineTicks;
        final String head = tail.seq < 0 ? genesisHash() : tail.hash;
        writer = new LogWriter(log, logFile, snapshotDir, sessionId, sessionIndex, tail.seq + 1, head);
        writer.start();
        logger = new AuditLogger(writer, clock);

        writeDirtyMarker();
        anchor.cleanShutdown = false;
        anchor.markDirty();

        logger.log("session_start", sessionStartData(overworld, tail, previousCrashed));
        FMLCommonHandler.instance()
            .bus()
            .register(this);
        log.info(
            "Speedrun audit session {} started (verdict: {}), logging to {}",
            sessionIndex,
            verifyVerdict,
            logFile);
    }

    public void stopping() {
        if (logger == null) {
            return;
        }
        final JsonObject data = new JsonObject();
        data.addProperty("uptimeTicks", clock.get() - sessionStartTicks);
        data.addProperty("onlineUptimeTicks", clock.getOnline() - sessionStartOnlineTicks);
        data.addProperty("reason", "stop");
        logger.log("session_end", data);
        writer.drain(15_000);
        // The world save that follows FMLServerStoppingEvent persists this — the anchor lands exactly on the
        // session_end line for a clean stop.
        publishAnchor(true);
    }

    public void stopped() {
        FMLCommonHandler.instance()
            .bus()
            .unregister(this);
        if (writer != null) {
            writer.close(5_000);
            if (!writer.isDead()) {
                dirtyMarker.delete();
            }
        }
        writer = null;
        logger = null;
        anchor = null;
    }

    // ------------------------------------------------------------------ ticking

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || anchor == null) {
            return;
        }
        if (!anchor.timingStarted) {
            // IGT convention: the clock is frozen until the first player movement — worldgen lag, spawn
            // staring and menu time don't count. Wall timestamps keep flowing regardless (RTA is unaffected).
            checkFirstMovement();
            return;
        }
        final long ticks = clock.increment(!server.getConfigurationManager().playerEntityList.isEmpty());
        if (ticks % ANCHOR_EVERY_TICKS == 0) {
            publishAnchor(false);
        }
    }

    private final java.util.Map<UUID, double[]> preTimingPositions = new java.util.HashMap<>();

    /**
     * The server can't see keypresses; deliberate horizontal movement is the proxy for "first WASD". One tick
     * of walking is ~0.21 blocks — the 0.03 threshold clears server jitter while catching a single step.
     * Vertical is ignored so a spawn-platform drop can't start the timer.
     */
    private void checkFirstMovement() {
        for (Object o : server.getConfigurationManager().playerEntityList) {
            final net.minecraft.entity.player.EntityPlayerMP player = (net.minecraft.entity.player.EntityPlayerMP) o;
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
            if (dx * dx + dz * dz > 0.0009) {
                anchor.timingStarted = true;
                anchor.timingStartWallMs = System.currentTimeMillis();
                preTimingPositions.clear();
                final JsonObject data = new JsonObject();
                data.addProperty(
                    "uuid",
                    player.getGameProfile()
                        .getId()
                        .toString());
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

    // ------------------------------------------------------------------ chain resume

    private static final class Tail {

        int maxIndex = 0;
        File latestFile;
        long seq = -1;
        String hash = "";
        String json = "";
    }

    /**
     * The log directory is the chain's source of truth. Find the line with the highest seq across session
     * files (rollback-suffixed files included) and resume the chain from its exact bytes.
     */
    private Tail findTail() throws IOException {
        final Tail tail = new Tail();
        final File[] files = logDir.listFiles();
        if (files == null) {
            return tail;
        }
        for (File f : files) {
            final Matcher m = LOG_NAME.matcher(f.getName());
            if (!m.matches()) {
                continue;
            }
            tail.maxIndex = Math.max(tail.maxIndex, Integer.parseInt(m.group(1)));
            final byte[] lastLine = readLastLine(f);
            if (lastLine == null) {
                continue;
            }
            try {
                final JsonObject obj = new JsonParser().parse(new String(lastLine, StandardCharsets.UTF_8))
                    .getAsJsonObject();
                final long seq = obj.get("seq")
                    .getAsLong();
                if (seq > tail.seq) {
                    tail.seq = seq;
                    tail.hash = JsonUtil.sha256Hex(lastLine);
                    tail.latestFile = f;
                    tail.json = JsonUtil.GSON.toJson(obj);
                }
            } catch (RuntimeException e) {
                log.warn("Unparseable tail line in {} — verifier will flag it", f.getName());
            }
        }
        return tail;
    }

    /** Trailing-newline-tolerant backward scan for the last non-empty line's bytes. */
    static byte[] readLastLine(File file) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            long end = raf.length();
            while (end > 0) {
                raf.seek(end - 1);
                final int c = raf.read();
                if (c == '\n' || c == '\r') {
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
                raf.seek(start - 1);
                if (raf.read() == '\n') {
                    break;
                }
                start--;
            }
            final byte[] bytes = new byte[(int) (end - start)];
            raf.seek(start);
            raf.readFully(bytes);
            return bytes;
        }
    }

    /**
     * Tail-vs-anchor triage. The full line-by-line chain walk lives in {@code /audit verify}; this start-up
     * classification is what lands in the session_start line.
     */
    private String classify(Tail tail, boolean previousCrashed) {
        if (tail.seq < 0) {
            return anchor.lastSeq < 0 ? "NEW_WORLD" : "LOG_MISSING";
        }
        if (anchor.lastSeq < 0) {
            return "ANCHOR_MISSING";
        }
        if (anchor.lastSeq < tail.seq) {
            // Crash lag is bounded by the anchor cadence (100 ticks ≈ 5s of events); anything beyond one
            // autosave of slack on a CLEAN previous stop means the world went backwards relative to the log.
            return previousCrashed ? "CRASH_RECOVERY" : "WORLD_ROLLBACK";
        }
        if (anchor.lastSeq > tail.seq) {
            return "LOG_TRUNCATED";
        }
        return anchor.lastHash.equals(tail.hash) ? "OK" : "TAMPERED_TAIL";
    }

    private String genesisHash() {
        return JsonUtil.sha256Hex("gtnhspeedrunaudit:genesis:" + anchor.worldAuditUuid);
    }

    private File newLogFile() {
        File f = new File(logDir, String.format("audit-%06d.jsonl", sessionIndex));
        // A rollback rewinds the anchor-derived numbering but the old file is still on disk. Never overwrite —
        // the collision itself is evidence and the verifier reads the -r suffix as such.
        int r = 2;
        while (f.exists()) {
            f = new File(logDir, String.format("audit-%06d-r%d.jsonl", sessionIndex, r++));
        }
        return f;
    }

    // ------------------------------------------------------------------ session_start payload

    private JsonObject sessionStartData(WorldServer overworld, Tail tail, boolean previousCrashed) {
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
        // Vanilla's own lifetime tick counter. For a world adopted mid-run this is the defensible pre-audit
        // IGT baseline, and thereafter it advances in lockstep with the audit clock (unaffected by /time set)
        // — divergence between the two is a cross-check.
        data.addProperty("worldTotalTime", overworld.getTotalWorldTime());
        data.addProperty("mcVersion", server.getMinecraftVersion());
        data.addProperty("javaVersion", System.getProperty("java.version"));
        data.addProperty(
            "modCount",
            Loader.instance()
                .getActiveModList()
                .size());

        final JsonObject anchorObj = new JsonObject();
        anchorObj.addProperty("seq", anchor.lastSeq);
        anchorObj.addProperty("ticks", anchor.cumulativeTicks);
        anchorObj.addProperty("wall", anchor.lastWallMs);
        data.add("anchor", anchorObj);

        final JsonObject tailObj = new JsonObject();
        tailObj.addProperty("seq", tail.seq);
        tailObj.addProperty("hash", tail.hash);
        data.add("logTail", tailObj);

        if ("WORLD_ROLLBACK".equals(verifyVerdict) || "CRASH_RECOVERY".equals(verifyVerdict)) {
            final JsonObject rb = new JsonObject();
            rb.addProperty("eventsRewound", tail.seq - anchor.lastSeq);
            rb.addProperty("wallGapMs", System.currentTimeMillis() - anchor.lastWallMs);
            data.add("rollback", rb);
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

    private void writeDirtyMarker() throws IOException {
        final JsonObject marker = new JsonObject();
        marker.addProperty("sessionId", sessionId);
        marker.addProperty("sessionIndex", sessionIndex);
        marker.addProperty("startWallMs", System.currentTimeMillis());
        Files.write(
            dirtyMarker.toPath(),
            JsonUtil.GSON.toJson(marker)
                .getBytes(StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------ session-scoped dedupe

    private final java.util.Set<String> onceKeys = new java.util.HashSet<>();

    /** True the first time a key is seen this session. Synchronized: mixin sinks may call off-thread. */
    public boolean once(String key) {
        synchronized (onceKeys) {
            return onceKeys.add(key);
        }
    }

    // ------------------------------------------------------------------ on-demand actions

    /** Snapshot triggers registered by the snapshotters (phase 3/4); run on the server thread. */
    private final java.util.List<Runnable> snapshotHooks = new java.util.ArrayList<>();

    public void addSnapshotHook(Runnable hook) {
        snapshotHooks.add(hook);
    }

    public void snapshotNow(net.minecraft.command.ICommandSender sender) {
        if (snapshotHooks.isEmpty()) {
            com.gtnhspeedrun.audit.command.CommandAudit.reply(sender, "no snapshotters active");
            return;
        }
        for (Runnable hook : snapshotHooks) {
            hook.run();
        }
        com.gtnhspeedrun.audit.command.CommandAudit
            .reply(sender, snapshotHooks.size() + " snapshot(s) queued (written in background)");
    }

    /** Zips months of logs + snapshots and re-verifies the whole chain — background thread, files only. */
    public void export(net.minecraft.command.ICommandSender sender) {
        writer.drain(5_000);
        final com.gtnhspeedrun.audit.export.AuditExport export = new com.gtnhspeedrun.audit.export.AuditExport(
            auditDir,
            logDir,
            snapshotDir,
            anchor.worldAuditUuid,
            writer.publishedSeq(),
            clock.get(),
            clock.getOnline(),
            anchor.timingStartWallMs);
        com.gtnhspeedrun.audit.command.CommandAudit.reply(sender, "building export bundle…");
        final Thread t = new Thread(() -> {
            try {
                final File zip = export.run();
                com.gtnhspeedrun.audit.command.CommandAudit.reply(sender, "export ready: " + zip.getAbsolutePath());
            } catch (Exception e) {
                com.gtnhspeedrun.audit.command.CommandAudit.reply(sender, "export FAILED: " + e);
            }
        }, "SpeedrunAudit-Export");
        t.setDaemon(true);
        t.start();
    }

    // ------------------------------------------------------------------ accessors

    public AuditLogger logger() {
        return logger;
    }

    public ChainAnchorData anchor() {
        return anchor;
    }

    public File auditDir() {
        return auditDir;
    }

    public File logDir() {
        return logDir;
    }

    public File snapshotDir() {
        return snapshotDir;
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
