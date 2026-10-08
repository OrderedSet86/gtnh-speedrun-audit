package com.gtnhspeedrun.audit.selftest;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.block.Block;
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
import net.minecraftforge.common.util.BlockSnapshot;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.event.world.BlockEvent;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.gtnhspeedrun.audit.GtnhSpeedrunAudit;
import com.gtnhspeedrun.audit.compat.Compat;
import com.gtnhspeedrun.audit.core.JsonUtil;
import com.gtnhspeedrun.audit.core.SessionManager;
import com.gtnhspeedrun.audit.core.Verifier;
import com.gtnhspeedrun.audit.trackers.AuditSinks;
import com.gtnhspeedrun.audit.trackers.MultiblockPower;
import com.mojang.authlib.GameProfile;

import cpw.mods.fml.common.eventhandler.EventBus;
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
    /**
     * The fakes' events go only to the audit's own listeners. Posted on the global buses, every mod on a real
     * pack reacts to them too — and a login handler that sends a packet (ExtraUtilities' angel ring, first on
     * 2.9.0-RC-1) NPEs in FML's outbound dispatch, because the fakes have no netty channel.
     */
    private final EventBus bus = new EventBus();
    private final List<String[]> checks = new ArrayList<>();

    private EntityPlayerMP alice;
    private EntityPlayerMP bob;
    private int tick;
    private int step;
    private boolean done;
    private long pticksAtLogout = -1;
    private long ticksAtLogout = -1;

    public SelfTest(MinecraftServer server, SessionManager session, File resultDir, List<Object> auditListeners) {
        this.server = server;
        this.session = session;
        this.resultDir = resultDir;
        for (Object listener : auditListeners) {
            bus.register(listener);
        }
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
            // The check detail is one line; the trace is what names the mod when a real pack's handler throws.
            GtnhSpeedrunAudit.LOG.error("[selftest] step {} threw", step - 1, t);
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
                bus.post(new PlayerEvent.PlayerLoggedInEvent(alice));
                bus.post(new PlayerEvent.PlayerLoggedInEvent(bob));
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
                placeAe2GridHost(overworld);
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
                bus.post(new net.minecraftforge.event.entity.living.LivingDeathEvent(bob, DamageSource.outOfWorld));
                // a mob kill: the death line names the killer
                bus.post(
                    new net.minecraftforge.event.entity.living.LivingDeathEvent(
                        bob,
                        new net.minecraft.util.EntityDamageSource(
                            "mob",
                            new net.minecraft.entity.monster.EntityZombie(overworld))));
                bus.post(new net.minecraftforge.event.entity.player.AchievementEvent(alice, AchievementList.mineWood));
                bus.post(new PlayerEvent.PlayerChangedDimensionEvent(alice, 0, -1));
                bus.post(new PlayerEvent.ItemCraftedEvent(alice, new ItemStack(Items.diamond, 9), alice.inventory));
            }
            case 8 -> {
                // PlaceEvent reads placedBlock back from the world (Forge posts it after setBlock), so each
                // block goes in first — posting over air would make both assertions below vacuous.
                // Negative: an untracked vanilla block placement must log neither machine_placed nor block_placed.
                alice.inventory.setInventorySlotContents(alice.inventory.currentItem, new ItemStack(Blocks.dirt));
                place(bus, overworld, alice, 0, Blocks.dirt);
                // Positive: the self-test watches diamond_block (stand-in for the Stargate blocks) — two
                // placements, two block_placed lines.
                alice.inventory
                    .setInventorySlotContents(alice.inventory.currentItem, new ItemStack(Blocks.diamond_block));
                place(bus, overworld, alice, 1, Blocks.diamond_block);
                place(bus, overworld, alice, 2, Blocks.diamond_block);

                // Sink layer — everything below the mixin line (the mixins themselves only bind on the pack).
                AuditSinks.neiPacket(uuid(alice), "SelfTestAlice", 1);
                AuditSinks.neiCheat("give", uuid(alice), "SelfTestAlice", "minecraft:diamond@0", 64, null);
                AuditSinks.multiblockFormed("gt", "SelfTestMulti", "test.multi", 0, 1, 2, 3);
                AuditSinks.multiblockFormed("gt", "SelfTestMulti", "test.multi", 0, 1, 2, 3); // dedupe
                // Creative tab take logs; a packet that leaves the slot unchanged does not.
                final ItemStack creativeDiamonds = new ItemStack(Items.diamond, 64);
                AuditSinks.creativeSlot(alice, 36, creativeDiamonds, null, true);
                AuditSinks.creativeSlot(alice, 36, creativeDiamonds, creativeDiamonds.copy(), true);
                // Hatch tiers join the dedupe key: LV logs, LV again dedupes, the UV upgrade logs again.
                final MultiblockPower lv = new MultiblockPower(2, "LV", 0, null);
                final MultiblockPower uv = new MultiblockPower(2, "UV", 0, null);
                AuditSinks.multiblockFormed("gt", "SelfTestMulti", "test.multi", 0, 7, 8, 9, lv);
                AuditSinks.multiblockFormed("gt", "SelfTestMulti", "test.multi", 0, 7, 8, 9, lv);
                AuditSinks.multiblockFormed("gt", "SelfTestMulti", "test.multi", 0, 7, 8, 9, uv);
                AuditSinks.gtExplosion("SelfTestMachine", "test.machine", 0, 4, 5, 6, 42);
                AuditSinks.nbtEdit("player", "player SelfTestAlice", uuid(alice), "SelfTestAlice", "deadbeef", null);
                // Census-failure path: the real census only fails on API drift, so drive the reporter directly.
                AuditSinks.announceAe2CensusFailure(
                    java.util.Collections.singletonList(
                        AuditSinks.ae2CensusFailed(0, "grid", "selftest", new IllegalStateException("selftest"))));
                final Map<String, String> fakeMods = new HashMap<>();
                fakeMods.put("selftestmod", "1.0");
                final Object key = new Object();
                AuditSinks.clientModList(key, fakeMods);
                check("client modlist round-trip", fakeMods.equals(AuditSinks.takeClientMods(key)), null);
            }
            case 9 -> {
                bus.post(new PlayerEvent.PlayerLoggedOutEvent(alice));
                bus.post(new PlayerEvent.PlayerLoggedOutEvent(bob));
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

    /**
     * Give the AE2 census a grid to count, so {@code ae2_snapshot} is exercised rather than merely compiled.
     * One ME Interface is enough — any grid host forms a grid, and a grid holding nothing still produces a
     * census record. Resolved by registry name on purpose: SelfTest has to stay loadable on a server without
     * AE2, which is the whole reason AE2-typed code lives in the compat package.
     */
    private void placeAe2GridHost(WorldServer world) {
        if (!Compat.AE2.isLoaded()) {
            return;
        }
        final Object block = Block.blockRegistry.getObject("appliedenergistics2:tile.BlockInterface");
        if (!(block instanceof Block b)) {
            check("ae2 grid host block present", false, "appliedenergistics2:tile.BlockInterface not registered");
            return;
        }
        world.setBlock(10, 70, 10, b);
    }

    // ------------------------------------------------------------------ verification

    private static void place(EventBus bus, WorldServer world, EntityPlayerMP player, int x, Block block) {
        world.setBlock(x, 63, 0, block);
        bus.post(new BlockEvent.PlaceEvent(BlockSnapshot.getBlockSnapshot(world, x, 63, 0), Blocks.stone, player));
    }

    private static final String[] EXPECTED_TYPES = { "session_start", "player_join", "timing_started",
        "gamemode_change", "inv_snapshot", "key_item_first_seen", "command", "difficulty_change", "death",
        "achievement", "dim_first_visit", "key_item_craft", "nei_packet", "nei_cheat", "multiblock_formed",
        "gt_explosion", "nbt_edit", "ae2_census_failed", "block_placed", "player_leave", "dim_change",
        "creative_slot" };

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
        check(
            "block_placed only for tracked block ×2",
            counts.getOrDefault("block_placed", 0) == 2,
            "count=" + counts.getOrDefault("block_placed", 0));
        check("both players joined", counts.getOrDefault("player_join", 0) >= 2, null);
        check("gamemode changes ×2", counts.getOrDefault("gamemode_change", 0) >= 2, null);
        check("difficulty changes ×2", counts.getOrDefault("difficulty_change", 0) >= 2, null);
        check(
            "multiblock dedupe, hatch-tier change re-logs",
            counts.getOrDefault("multiblock_formed", 0) == 3,
            "count=" + counts.getOrDefault("multiblock_formed", 0));
        check(
            "creative_slot unchanged slot not logged",
            counts.getOrDefault("creative_slot", 0) == 1,
            "count=" + counts.getOrDefault("creative_slot", 0));
        check(
            "death killer logged",
            lines.stream()
                .anyMatch(
                    line -> "death".equals(
                        line.get("t")
                            .getAsString())
                        && "Zombie".equals(
                            line.getAsJsonObject("data")
                                .has("killer")
                                    ? line.getAsJsonObject("data")
                                        .get("killer")
                                        .getAsString()
                                    : null)),
            null);
        check(
            "multiblock energyTier logged",
            lines.stream()
                .anyMatch(
                    line -> "multiblock_formed".equals(
                        line.get("t")
                            .getAsString())
                        && line.getAsJsonObject("data")
                            .has("energyTier")
                        && "UV".equals(
                            line.getAsJsonObject("data")
                                .get("energyTier")
                                .getAsString())),
            null);
        // Conditional rather than in EXPECTED_TYPES: without AE2 there is no census to assert on, and the
        // jar is meant to run on servers that do not have it.
        if (Compat.AE2.isLoaded()) {
            check(
                "event logged: ae2_snapshot",
                counts.getOrDefault("ae2_snapshot", 0) > 0,
                "count=" + counts.getOrDefault("ae2_snapshot", 0));
        }

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
