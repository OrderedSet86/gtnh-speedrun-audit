package com.gtnhspeedrun.audit.selftest;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.stats.AchievementList;
import net.minecraft.util.DamageSource;
import net.minecraft.world.EnumDifficulty;
import net.minecraft.world.WorldServer;
import net.minecraft.world.WorldSettings;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.util.BlockSnapshot;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.event.world.BlockEvent;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.gtnhspeedrun.audit.GtnhSpeedrunAudit;
import com.gtnhspeedrun.audit.core.JsonUtil;
import com.gtnhspeedrun.audit.core.SessionManager;
import com.gtnhspeedrun.audit.core.Verifier;
import com.gtnhspeedrun.audit.trackers.AuditSinks;
import com.mojang.authlib.GameProfile;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/**
 * Headless "fake client": performs every loggable action against a live dev dedicated server, then reads
 * the actual JSONL back and asserts the trackers recorded it. Activated by
 * {@code -Dspeedrunaudit.selftest=<resultDir>} (dormant otherwise, WorldgenProbe convention); the drill
 * script layers multi-boot verdict tests on top.
 *
 * <p>
 * Unlike the probe this MUST let the server tick — the run clocks, movement detection and the
 * gamemode/difficulty polls are all tick-driven — so it is a step machine on ServerTickEvent, a few ticks
 * between steps, not a synchronous block. Two fake players exercise the multi-player paths; the same flag
 * on a real pack server runs the scenario with the mixin integrations live.
 *
 * <p>
 * Results: {@code <resultDir>/selftest.json} plus a single stdout marker line SELFTEST PASSED / FAILED.
 * Scripts assert on those (no exit codes — the server shuts down normally).
 */
public final class SelfTest {

    public static final String PROPERTY = "speedrunaudit.selftest";

    /** Ticks between steps — enough for every per-tick poll to observe the change made by the last step. */
    private static final int STEP_GAP = 5;

    private final MinecraftServer server;
    private final SessionManager session;
    private final File resultDir;
    private final List<String[]> checks = new ArrayList<>();

    private EntityPlayerMP alice;
    private EntityPlayerMP bob;
    private int tick;
    private int step;
    private boolean done;
    private long pticksAtLogout = -1;
    private long ticksAtLogout = -1;

    public SelfTest(MinecraftServer server, SessionManager session, File resultDir) {
        this.server = server;
        this.session = session;
        this.resultDir = resultDir;
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || done) {
            return;
        }
        tick++;
        if (tick % STEP_GAP != 0) {
            return;
        }
        try {
            runStep(step++);
        } catch (Throwable t) {
            check("step " + (step - 1) + " threw", false, String.valueOf(t));
            finish();
        }
    }

    @SuppressWarnings("unchecked")
    private void runStep(int step) {
        final WorldServer overworld = server.worldServers[0];
        switch (step) {
            case 0 -> { // multiplayer join: two fake players
                check(
                    "clock frozen before movement",
                    session.logger()
                        .clock()
                        .get() == 0,
                    "ticks=" + session.logger()
                        .clock()
                        .get());
                alice = FakePlayerFactory.get(
                    overworld,
                    new GameProfile(
                        UUID.nameUUIDFromBytes("selftest-alice".getBytes(StandardCharsets.UTF_8)),
                        "SelfTestAlice"));
                bob = FakePlayerFactory.get(
                    overworld,
                    new GameProfile(
                        UUID.nameUUIDFromBytes("selftest-bob".getBytes(StandardCharsets.UTF_8)),
                        "SelfTestBob"));
                alice.setPositionAndRotation(0, 64, 0, 0, 0);
                bob.setPositionAndRotation(0, 64, 0, 0, 0);
                // Vanilla broadcasts (time sync etc.) hit every listed player's net handler each tick, so
                // the fakes need a real one — over an unconnected NetworkManager, whose flush is
                // channel-guarded, packets just queue harmlessly.
                new net.minecraft.network.NetHandlerPlayServer(
                    server,
                    new net.minecraft.network.NetworkManager(false),
                    alice);
                new net.minecraft.network.NetHandlerPlayServer(
                    server,
                    new net.minecraft.network.NetworkManager(false),
                    bob);
                server.getConfigurationManager().playerEntityList.add(alice);
                server.getConfigurationManager().playerEntityList.add(bob);
                FMLCommonHandler.instance()
                    .bus()
                    .post(new PlayerEvent.PlayerLoggedInEvent(alice));
                FMLCommonHandler.instance()
                    .bus()
                    .post(new PlayerEvent.PlayerLoggedInEvent(bob));
            }
            case 1 -> // first WASD proxy: one horizontal step between polls
                alice.setPosition(alice.posX + 0.5, alice.posY, alice.posZ);
            case 2 -> {
                check(
                    "clock running after movement",
                    session.logger()
                        .clock()
                        .get() > 0,
                    null);
                check(
                    "online ticks running",
                    session.logger()
                        .clock()
                        .getOnline() > 0,
                    null);
                alice.theItemInWorldManager.setGameType(WorldSettings.GameType.CREATIVE);
            }
            case 3 -> {
                alice.theItemInWorldManager.setGameType(WorldSettings.GameType.SURVIVAL);
                alice.inventory.setInventorySlotContents(0, new ItemStack(Items.diamond, 3));
            }
            case 4 -> session.snapshotNow(server);
            case 5 -> {
                server.getCommandManager()
                    .executeCommand(server, "help");
                server.getCommandManager()
                    .executeCommand(alice, "help");
                overworld.difficultySetting = EnumDifficulty.PEACEFUL;
            }
            case 6 -> overworld.difficultySetting = EnumDifficulty.NORMAL;
            case 7 -> {
                // FakePlayer is invulnerable and no-ops onDeath by design, so real damage can never reach
                // LivingDeathEvent — post the event itself; the bus is the tracker's contract.
                MinecraftForge.EVENT_BUS
                    .post(new net.minecraftforge.event.entity.living.LivingDeathEvent(bob, DamageSource.outOfWorld));
                MinecraftForge.EVENT_BUS
                    .post(new net.minecraftforge.event.entity.player.AchievementEvent(alice, AchievementList.mineWood));
                FMLCommonHandler.instance()
                    .bus()
                    .post(new PlayerEvent.PlayerChangedDimensionEvent(alice, 0, -1));
                FMLCommonHandler.instance()
                    .bus()
                    .post(new PlayerEvent.ItemCraftedEvent(alice, new ItemStack(Items.diamond, 9), alice.inventory));
            }
            case 8 -> {
                // Negative: a vanilla block placement must NOT log machine_placed.
                alice.inventory.setInventorySlotContents(alice.inventory.currentItem, new ItemStack(Blocks.stone));
                MinecraftForge.EVENT_BUS.post(
                    new BlockEvent.PlaceEvent(BlockSnapshot.getBlockSnapshot(overworld, 0, 63, 0), Blocks.dirt, alice));

                // Sink layer — everything below the mixin line (the mixins themselves only bind on the pack).
                AuditSinks.neiPacket(uuid(alice), "SelfTestAlice", 1);
                AuditSinks.neiCheat("give", uuid(alice), "SelfTestAlice", "minecraft:diamond@0", 64, null);
                AuditSinks.multiblockFormed("gt", "SelfTestMulti", "test.multi", 0, 1, 2, 3);
                AuditSinks.multiblockFormed("gt", "SelfTestMulti", "test.multi", 0, 1, 2, 3); // dedupe
                AuditSinks.gtExplosion("SelfTestMachine", "test.machine", 0, 4, 5, 6, 42);
                AuditSinks.nbtEdit("player", "player SelfTestAlice", uuid(alice), "SelfTestAlice", "deadbeef", null);
                final Map<String, String> fakeMods = new HashMap<>();
                fakeMods.put("selftestmod", "1.0");
                final Object key = new Object();
                AuditSinks.clientModList(key, fakeMods);
                check("client modlist round-trip", fakeMods.equals(AuditSinks.takeClientMods(key)), null);
            }
            case 9 -> {
                FMLCommonHandler.instance()
                    .bus()
                    .post(new PlayerEvent.PlayerLoggedOutEvent(alice));
                FMLCommonHandler.instance()
                    .bus()
                    .post(new PlayerEvent.PlayerLoggedOutEvent(bob));
                server.getConfigurationManager().playerEntityList.remove(alice);
                server.getConfigurationManager().playerEntityList.remove(bob);
                pticksAtLogout = session.logger()
                    .clock()
                    .getOnline();
                ticksAtLogout = session.logger()
                    .clock()
                    .get();
            }
            case 10 -> {
                check(
                    "pticks frozen after logout",
                    session.logger()
                        .clock()
                        .getOnline() == pticksAtLogout,
                    null);
                check(
                    "igt still running after logout",
                    session.logger()
                        .clock()
                        .get() > ticksAtLogout,
                    null);
                verifyLog();
                finish();
            }
            default -> {}
        }
    }

    // ------------------------------------------------------------------ verification

    private static final String[] EXPECTED_TYPES = { "session_start", "player_join", "timing_started",
        "gamemode_change", "inv_snapshot", "key_item_first_seen", "command", "difficulty_change", "death",
        "achievement", "dim_first_visit", "key_item_craft", "nei_packet", "nei_cheat", "multiblock_formed",
        "gt_explosion", "nbt_edit", "player_leave" };

    private void verifyLog() {
        session.logger()
            .writer()
            .drain(10_000);
        final Map<String, Integer> counts = new HashMap<>();
        final List<JsonObject> lines = new ArrayList<>();
        try {
            final File[] files = session.logDir()
                .listFiles((d, n) -> n.endsWith(".jsonl"));
            for (File f : files) {
                for (String line : Files.readAllLines(f.toPath(), StandardCharsets.UTF_8)) {
                    if (!line.isEmpty()) {
                        final JsonObject o = new JsonParser().parse(line)
                            .getAsJsonObject();
                        lines.add(o);
                        counts.merge(
                            o.get("t")
                                .getAsString(),
                            1,
                            Integer::sum);
                    }
                }
            }
        } catch (Exception e) {
            check("log readable", false, String.valueOf(e));
            return;
        }

        for (String type : EXPECTED_TYPES) {
            check("event logged: " + type, counts.getOrDefault(type, 0) > 0, null);
        }
        check(
            "machine_placed NOT logged for vanilla block",
            counts.getOrDefault("machine_placed", 0) == 0,
            "count=" + counts.getOrDefault("machine_placed", 0));
        check("both players joined", counts.getOrDefault("player_join", 0) >= 2, null);
        check("gamemode changes ×2", counts.getOrDefault("gamemode_change", 0) >= 2, null);
        check("difficulty changes ×2", counts.getOrDefault("difficulty_change", 0) >= 2, null);
        check(
            "multiblock dedupe",
            counts.getOrDefault("multiblock_formed", 0) == 1,
            "count=" + counts.getOrDefault("multiblock_formed", 0));

        boolean sawNonzeroBeforeTiming = false;
        boolean timingSeen = false;
        for (JsonObject line : lines) {
            final String t = line.get("t")
                .getAsString();
            if (t.equals("timing_started")) {
                timingSeen = true;
            }
            if (!timingSeen && line.get("ticks")
                .getAsLong() != 0) {
                sawNonzeroBeforeTiming = true;
            }
        }
        check("no ticks before timing_started", !sawNonzeroBeforeTiming, null);

        try {
            final Verifier.Result result = Verifier.verify(
                session.logDir(),
                session.anchor().worldAuditUuid,
                session.logger()
                    .writer()
                    .publishedSeq());
            check("chain verifies", "OK".equals(result.verdict), result.verdict);
        } catch (Exception e) {
            check("chain verifies", false, String.valueOf(e));
        }
    }

    private void check(String name, boolean ok, String detail) {
        checks.add(new String[] { name, ok ? "ok" : "FAIL", detail == null ? "" : detail });
        GtnhSpeedrunAudit.LOG
            .info("[selftest] {} — {}{}", ok ? "ok  " : "FAIL", name, detail == null ? "" : " (" + detail + ")");
    }

    private void finish() {
        done = true;
        final List<String> failures = new ArrayList<>();
        final com.google.gson.JsonArray arr = new com.google.gson.JsonArray();
        for (String[] c : checks) {
            final JsonObject o = new JsonObject();
            o.addProperty("name", c[0]);
            o.addProperty("ok", "ok".equals(c[1]));
            if (!c[2].isEmpty()) {
                o.addProperty("detail", c[2]);
            }
            arr.add(o);
            if (!"ok".equals(c[1])) {
                failures.add(c[0]);
            }
        }
        final JsonObject report = new JsonObject();
        report.addProperty("pass", failures.isEmpty());
        report.add("checks", arr);
        try {
            resultDir.mkdirs();
            Files.write(
                new File(resultDir, "selftest.json").toPath(),
                JsonUtil.GSON.toJson(report)
                    .getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            GtnhSpeedrunAudit.LOG.error("[selftest] could not write report", e);
        }
        if (failures.isEmpty()) {
            GtnhSpeedrunAudit.LOG.info("SELFTEST PASSED ({} checks)", checks.size());
        } else {
            GtnhSpeedrunAudit.LOG.error("SELFTEST FAILED: {}", String.join(", ", failures));
        }
        server.initiateShutdown();
    }

    private static String uuid(EntityPlayerMP player) {
        return player.getGameProfile()
            .getId()
            .toString();
    }
}
