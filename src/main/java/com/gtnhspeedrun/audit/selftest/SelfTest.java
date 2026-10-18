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
import net.minecraft.nbt.NBTTagCompound;
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
import com.gtnhspeedrun.audit.compat.NbtEditDrill;
import com.gtnhspeedrun.audit.core.JsonUtil;
import com.gtnhspeedrun.audit.core.SessionManager;
import com.gtnhspeedrun.audit.core.Verifier;
import com.gtnhspeedrun.audit.trackers.AuditSinks;
import com.gtnhspeedrun.audit.trackers.InvseeViews;
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
    /** Stand-ins for the GT machine block and the Matter Manipulator, so the drill runs without either mod. */
    public static final String STAND_IN_MACHINE_BLOCK = "minecraft:iron_block";
    /** GT MetaTileEntity id of the Lapotronic Supercapacitor controller. */
    private static final int LSC_CONTROLLER_META = 13106;
    private static final long SEEDED_WIRELESS_EU = 12_345L;
    public static final String STAND_IN_MANIPULATOR_PREFIX = "minecraft:stick@";

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
    /** The Active Transformer's live {direction, rotation, flip}, read at step 30. */
    private String[] ttFacing;
    private long ticksAtLogout = -1;
    /** { before, after } hashes of the live /nbtedit drill; null when ServerUtilities is absent. */
    private String[] nbtEditHashes;

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
                alice.setPositionAndRotation(ox(0), oy(1), oz(0), 0, 0);
                bob.setPositionAndRotation(ox(0), oy(1), oz(0), 0, 0);
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
                // creative_block: an untracked, unwatched block placed and broken in creative. Meta 5 over air
                // (meta 0) checks the placed meta is read from the world, not from the replaced-block snapshot
                place(bus, overworld, alice, ox(5), oy(0), oz(0), Blocks.stained_glass, 5);
                bus.post(new BlockEvent.BreakEvent(ox(5), oy(0), oz(0), overworld, Blocks.stained_glass, 5, alice));
                // the self-test's node census watches chests (stand-in for Thaumcraft nodes)
                overworld.setBlock(ox(12), oy(7), oz(12), Blocks.chest);
                // a save before the step-5 saving-off edge, so that line carries lastSave. One step before the AE2
                // grid host goes down: saving in the tick it is placed kept its grid from forming
                try {
                    overworld.saveAllChunks(false, null);
                } catch (net.minecraft.world.MinecraftException e) {
                    check("world save for lastSave", false, String.valueOf(e));
                }
            }
            case 3 -> {
                alice.theItemInWorldManager.setGameType(WorldSettings.GameType.SURVIVAL);
                alice.inventory.setInventorySlotContents(0, new ItemStack(Items.diamond, 3));
                placeAe2GridHost(overworld);
                seedPowerCensus(overworld, alice);
                buildTecTechMulti(overworld, alice);
                logCrusherFormation(overworld, alice);
            }
            case 4 -> session.snapshotNow(server);
            case 5 -> {
                if (Compat.WORLDEDIT.isLoaded()) {
                    final String problem = com.gtnhspeedrun.audit.compat.WorldEditFixture
                        .edit(overworld, ox(20), oy(17), oz(20));
                    if (problem != null) {
                        check("worldedit edit ran", false, problem);
                    }
                }
                server.getCommandManager()
                    .executeCommand(server, "help");
                server.getCommandManager()
                    .executeCommand(alice, "help");
                // gamerule_change (polled) and command_output (gamerule is on the capture allowlist)
                server.getCommandManager()
                    .executeCommand(server, "gamerule doFireTick false");
                // off the allowlist: no command_output
                server.getCommandManager()
                    .executeCommand(server, "seed");
                overworld.difficultySetting = EnumDifficulty.PEACEFUL;
                // what a ServerUtilities backup or /save-off does
                overworld.levelSaving = true;
            }
            case 6 -> {
                overworld.difficultySetting = EnumDifficulty.NORMAL;
                overworld.levelSaving = false;
                server.getCommandManager()
                    .executeCommand(server, "gamerule doFireTick true");
                // /trash_can's chest: open it with something inside, then close it next step
                final net.minecraft.inventory.InventoryBasic trash = new net.minecraft.inventory.InventoryBasic(
                    net.minecraft.util.StatCollector.translateToLocal("key.serverutilities.trash"),
                    true,
                    36);
                trash.setInventorySlotContents(0, new ItemStack(Items.diamond, 5));
                alice.openContainer = new net.minecraft.inventory.ContainerChest(alice.inventory, trash);
                bus.post(new TickEvent.PlayerTickEvent(TickEvent.Phase.END, alice));
            }
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
                alice.openContainer = alice.inventoryContainer; // closes the trash chest
                bus.post(new TickEvent.PlayerTickEvent(TickEvent.Phase.END, alice));
            }
            case 8 -> {
                // PlaceEvent reads placedBlock back from the world (Forge posts it after setBlock), so each
                // block goes in first — posting over air would make both assertions below vacuous.
                // Negative: an untracked vanilla block placement must log neither machine_placed nor block_placed.
                alice.inventory.setInventorySlotContents(alice.inventory.currentItem, new ItemStack(Blocks.dirt));
                place(bus, overworld, alice, ox(0), oy(0), oz(0), Blocks.dirt);
                // Positive: the self-test watches diamond_block (stand-in for the Stargate blocks) — two
                // placements, two block_placed lines.
                alice.inventory
                    .setInventorySlotContents(alice.inventory.currentItem, new ItemStack(Blocks.diamond_block));
                place(bus, overworld, alice, ox(1), oy(0), oz(0), Blocks.diamond_block);
                place(bus, overworld, alice, ox(2), oy(0), oz(0), Blocks.diamond_block);
                // watched_block break (the self-test watches diamond_block)
                bus.post(new BlockEvent.BreakEvent(ox(2), oy(0), oz(0), overworld, Blocks.diamond_block, 0, alice));
                driveManipulator(overworld);
                // a machine placed with a tool in the main hand (a Vajra or a StructureLib projector on the pack):
                // the line names the tool as itemKey and the machine from the world
                alice.inventory
                    .setInventorySlotContents(alice.inventory.currentItem, new ItemStack(Items.diamond_pickaxe));
                place(bus, overworld, alice, ox(3), oy(0), oz(0), Blocks.iron_block);

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
                // Coil/structure tiers join the dedupe key too: LV logs, LV again dedupes, HV logs again.
                final MultiblockPower coilLv = new MultiblockPower(
                    1,
                    "EV",
                    0,
                    null,
                    java.util.Collections.singletonMap("mCoilLevel", "LV"));
                final MultiblockPower coilHv = new MultiblockPower(
                    1,
                    "EV",
                    0,
                    null,
                    java.util.Collections.singletonMap("mCoilLevel", "HV"));
                AuditSinks.multiblockFormed("gt", "SelfTestEbf", "test.ebf", 0, 10, 11, 12, coilLv);
                AuditSinks.multiblockFormed("gt", "SelfTestEbf", "test.ebf", 0, 10, 11, 12, coilLv);
                AuditSinks.multiblockFormed("gt", "SelfTestEbf", "test.ebf", 0, 10, 11, 12, coilHv);
                driveFormationMarker();
                AuditSinks.gtPowerfail("SelfTestMachine", "test.machine", 0, 4, 5, 6);
                AuditSinks.bloodAltarTier(0, 20, 64, 20, 1, 2);
                AuditSinks.trashInsert(alice, null, new ItemStack(Items.diamond, 2));
                final com.google.gson.JsonArray invseeChanges = new com.google.gson.JsonArray();
                invseeChanges.add(new JsonObject());
                driveInvseeViews(overworld, invseeChanges);
                final JsonObject giveExtra = new JsonObject();
                giveExtra.addProperty("inserted", 0);
                AuditSinks.neiCheat("give", uuid(alice), "SelfTestAlice", "minecraft:diamond@0", 64, null, giveExtra);
                final NBTTagCompound beforeEdit = new NBTTagCompound();
                beforeEdit.setInteger("charge", 5);
                AuditSinks.nbtEdit(
                    "player",
                    "player SelfTestAlice",
                    uuid(alice),
                    "SelfTestAlice",
                    "deadbeef",
                    null,
                    "cafebabe",
                    JsonUtil.nbtToB64(beforeEdit),
                    null);
                // The mixin itself, through ServerUtilities' own packet handler — only on a pack server.
                nbtEditHashes = NbtEditDrill.editChest(overworld, alice, ox(5), oy(0), oz(0));
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
            // steps 10-33 wait out TecTech's start-up structure check, ~100 ticks after step 3 placed its controller
            case 30 -> recheckTecTechMulti(overworld);
            case 31 -> rotateTecTechMulti(overworld);
            case 34 -> {
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
     * /inv view outcomes, through the view registry below the mixin: a target who logs in mid-view, two views of one
     * offline target, a view replaced by another, and (at step 9's logouts) a viewer who leaves with a view open.
     */
    private void driveInvseeViews(WorldServer overworld, com.google.gson.JsonArray changes) {
        final UUID carolId = UUID.nameUUIDFromBytes("selftest-carol".getBytes(StandardCharsets.UTF_8));
        final UUID daveId = UUID.nameUUIDFromBytes("selftest-dave".getBytes(StandardCharsets.UTF_8));
        final UUID aliceId = alice.getUniqueID();
        // target logs in mid-view: the write-back is skipped
        final String joined = InvseeViews.open(aliceId, "SelfTestAlice", carolId, "SelfTestCarol", false);
        AuditSinks.invseeClick(joined, alice, "SelfTestCarol", carolId.toString(), false, 3, 0, 0, changes, null, null);
        InvseeViews.clicked(joined, true);
        final EntityPlayerMP carol = FakePlayerFactory.get(overworld, new GameProfile(carolId, "SelfTestCarol"));
        bus.post(new PlayerEvent.PlayerLoggedInEvent(carol));
        InvseeViews.end(joined, "closed", aliceId, "SelfTestAlice", "SelfTestCarol", true);
        // two views of one offline target: last writer wins
        final String first = InvseeViews.open(aliceId, "SelfTestAlice", daveId, "SelfTestDave", false);
        final String second = InvseeViews.open(bob.getUniqueID(), "SelfTestBob", daveId, "SelfTestDave", false);
        InvseeViews.clicked(first, true);
        InvseeViews.clicked(second, true);
        InvseeViews.end(first, "closed", aliceId, "SelfTestAlice", "SelfTestDave", false);
        InvseeViews.end(second, "closed", bob.getUniqueID(), "SelfTestBob", "SelfTestDave", false);
        // replaced without a close
        final String replaced = InvseeViews.open(aliceId, "SelfTestAlice", daveId, "SelfTestDave", false);
        InvseeViews.clicked(replaced, true);
        InvseeViews.end(replaced, "replaced", aliceId, "SelfTestAlice", null, false);
        // left open: bob's logout at step 9 ends it
        final String abandoned = InvseeViews.open(bob.getUniqueID(), "SelfTestBob", daveId, "SelfTestDave", false);
        InvseeViews.clicked(abandoned, true);
    }

    /**
     * A Matter Manipulator build, with a stick for the manipulator and iron blocks for GT machines. The manipulator
     * fires PlaceEvent before it places, with the block already there, and places without an event. Spot 20 gets a
     * machine (checked twice), spot 21 already had one, spot 22 stays air: one bulk_placed with one machine and one
     * checked spot. Spots 20 and 22 are cleared first, so terrain cannot stand in for a machine.
     */
    private void driveManipulator(WorldServer world) {
        alice.inventory.setInventorySlotContents(alice.inventory.currentItem, new ItemStack(Items.stick));
        world.setBlockToAir(ox(20), oy(0), oz(0));
        world.setBlockToAir(ox(22), oy(0), oz(0));
        world.setBlock(ox(21), oy(0), oz(0), Blocks.iron_block);
        for (int x : new int[] { 20, 20, 21, 22 }) {
            bus.post(
                new BlockEvent.PlaceEvent(
                    BlockSnapshot.getBlockSnapshot(world, ox(x), oy(0), oz(0)),
                    Blocks.stone,
                    alice));
        }
        world.setBlock(ox(20), oy(0), oz(0), Blocks.iron_block);
    }

    /**
     * The GT formation marker, below the mixin: logged once, not again when the marker comes back with the chunk,
     * again on a tier change, and again when a backup restores a controller without the marker. Three lines.
     */
    private void driveFormationMarker() {
        final MultiblockPower ev = new MultiblockPower(1, "EV", 0, null);
        final MultiblockPower iv = new MultiblockPower(1, "IV", 0, null);
        final String marker = AuditSinks.gtMultiblockFormed(null, "SelfTestMarked", "test.marked", 0, 13, 14, 15, ev);
        final String reloaded = AuditSinks
            .gtMultiblockFormed(marker, "SelfTestMarked", "test.marked", 0, 13, 14, 15, ev);
        check("formation marker unchanged when nothing logged", marker != null && marker.equals(reloaded), marker);
        AuditSinks.gtMultiblockFormed(marker, "SelfTestMarked", "test.marked", 0, 13, 14, 15, iv);
        AuditSinks.gtMultiblockFormed(null, "SelfTestMarked", "test.marked", 0, 13, 14, 15, ev);
    }

    /**
     * Give the power census an LSC controller to read and a wireless balance to report. By registry name and
     * reflection, like the AE2 grid host: SelfTest has to stay loadable on a server without GregTech. The
     * controller is unformed, so its capacitor counts are empty, but every field read is the real one.
     */
    private void seedPowerCensus(WorldServer world, EntityPlayerMP player) {
        if (!Compat.GT5.isLoaded()) {
            return;
        }
        // the controller goes on top of the stone, so that spot must be free whatever the terrain
        world.setBlock(ox(14), oy(6), oz(10), Blocks.stone);
        world.setBlockToAir(ox(14), oy(7), oz(10));
        final Object block = Block.blockRegistry.getObject("gregtech:gt.blockmachines");
        if (!(block instanceof Block b)) {
            check("gt machine block present", false, "gregtech:gt.blockmachines not registered");
            return;
        }
        new ItemStack(b, 1, LSC_CONTROLLER_META)
            .tryPlaceItemIntoWorld(player, world, ox(14), oy(6), oz(10), 1, 0.5f, 1f, 0.5f);
        try {
            Class.forName("gregtech.common.misc.WirelessNetworkManager")
                .getMethod("addEUToGlobalEnergyMap", UUID.class, long.class)
                .invoke(null, player.getUniqueID(), SEEDED_WIRELESS_EU);
        } catch (ReflectiveOperationException | RuntimeException e) {
            check("wireless EU seeded", false, String.valueOf(e));
        }
    }

    /** A forced re-check of the formed Active Transformer. Its footprint is unchanged, so it logs nothing. */
    private void recheckTecTechMulti(WorldServer world) {
        if (!Compat.GT5.isLoaded()) {
            return;
        }
        final String problem = com.gtnhspeedrun.audit.compat.TecTechFixture.recheck(world, ox(8), oy(0), oz(8));
        check("tectech multiblock re-checked", problem == null, problem);
        ttFacing = com.gtnhspeedrun.audit.compat.TecTechFixture.facing(world, ox(8), oy(0), oz(8));
    }

    /** A wrench-style quarter turn of the formed Active Transformer: one multiblock_footprint, rotation CLOCKWISE. */
    private void rotateTecTechMulti(WorldServer world) {
        if (!Compat.GT5.isLoaded()) {
            return;
        }
        final String problem = com.gtnhspeedrun.audit.compat.TecTechFixture
            .rotateAndRecheck(world, ox(8), oy(0), oz(8));
        check("tectech multiblock rotated and re-formed", problem == null, problem);
    }

    /**
     * An Extreme Entity Crusher's formation, logged on this dedicated server the way the structure-check hook logs
     * it. v0.8.0 crashed the server here; the line it writes is counted with the other checks.
     */
    private void logCrusherFormation(WorldServer world, EntityPlayerMP player) {
        if (!Compat.GT5.isLoaded()) {
            return;
        }
        final String problem = com.gtnhspeedrun.audit.compat.FormationSideCheck
            .logCrusherFormation(world, player, ox(-6), oy(0), oz(-6));
        check("extreme entity crusher formation logged without a throw", problem == null, problem);
    }

    /** An Active Transformer that can only form through TTMultiblockBase.onPostTick's own structure check. */
    private void buildTecTechMulti(WorldServer world, EntityPlayerMP player) {
        if (!Compat.GT5.isLoaded()) {
            return;
        }
        final String problem = com.gtnhspeedrun.audit.compat.TecTechFixture.build(world, player, ox(8), oy(0), oz(8));
        if (problem != null) {
            check("tectech multiblock built", false, problem);
        }
    }

    /**
     * Every block the scenario puts in the world goes at an offset from this spot, 40 blocks above the world spawn,
     * in the spawn chunks: they are generated at startup and stay loaded with nobody online. Fixed coordinates
     * passed or failed by seed: Hodgepodge's chunk-generation throttle hands back an empty chunk for one never
     * generated, and a setBlock into it is dropped without an error. Staying loaded also lets a restart's own
     * structure check run, so the restart drill can show the TecTech fixture logs nothing new.
     */
    private net.minecraft.util.ChunkCoordinates origin;

    private net.minecraft.util.ChunkCoordinates origin() {
        if (origin == null) {
            final net.minecraft.util.ChunkCoordinates spawn = server.worldServers[0].getSpawnPoint();
            origin = new net.minecraft.util.ChunkCoordinates(spawn.posX, spawn.posY + 40, spawn.posZ);
        }
        return origin;
    }

    private int ox(int dx) {
        return origin().posX + dx;
    }

    private int oy(int dy) {
        return origin().posY + dy;
    }

    private int oz(int dz) {
        return origin().posZ + dz;
    }

    /**
     * Give the AE2 census a grid to count, so {@code ae2_snapshot} is exercised rather than merely compiled: an
     * ME Interface holding one processing pattern for {@link #AE2_CRAFTABLE_KEY}, powered so the pattern is
     * provided, which the census must report as a count-0 craftable entry. The AE2-typed part lives in the
     * compat package, so SelfTest stays loadable on a server without AE2.
     */
    private void placeAe2GridHost(WorldServer world) {
        if (!Compat.AE2.isLoaded()) {
            return;
        }
        final String problem = com.gtnhspeedrun.audit.compat.Ae2Fixture
            .build(world, ox(10), oy(7), oz(10), new ItemStack(Blocks.cobblestone), new ItemStack(Items.nether_star));
        if (problem != null) {
            check("ae2 grid with a pattern built", false, problem);
        }
    }

    /** Nothing in the scenario stocks this, so its census entry can only come from the pattern. */
    private static final String AE2_CRAFTABLE_KEY = "minecraft:nether_star@0";

    // ------------------------------------------------------------------ verification

    /** The fixture's one edit session, counted exactly: what it placed, what it replaced, and the chest's tile. */
    private void checkWorldEdit(List<JsonObject> lines) {
        final List<JsonObject> edits = lines.stream()
            .filter(
                l -> "worldedit_edit".equals(
                    l.get("t")
                        .getAsString()))
            .map(l -> l.getAsJsonObject("data"))
            .collect(java.util.stream.Collectors.toList());
        if (edits.size() != 1) {
            check("worldedit_edit: one line for the fixture's session", false, "lines=" + edits);
            return;
        }
        final JsonObject e = edits.get(0);
        final JsonObject placed = e.getAsJsonObject("placed");
        final JsonObject removed = e.getAsJsonObject("removed");
        final boolean ok = e.get("changed")
            .getAsLong() == 6
            && placed.entrySet()
                .size() == 3
            && placed.get("minecraft:stone@0")
                .getAsLong() == 3
            && placed.get("minecraft:glass@0")
                .getAsLong() == 2
            && placed.get("minecraft:chest@0")
                .getAsLong() == 1
            && removed.entrySet()
                .size() == 1
            && removed.get("minecraft:air@0")
                .getAsLong() == 6
            && e.get("tileEntitiesTotal")
                .getAsInt() == 1
            && e.getAsJsonArray("tileEntities")
                .get(0)
                .getAsJsonArray()
                .get(3)
                .getAsString()
                .equals("minecraft:chest@0")
            && e.get("dim")
                .getAsInt() == 0;
        check("worldedit_edit: exact placed/removed counts and the chest's tile", ok, String.valueOf(e));
    }

    /** The pattern's output appears in an AE2 census file as a count-0 entry flagged craftable. */
    private void checkAe2Craftable(List<JsonObject> lines) {
        final List<String> seen = new ArrayList<>();
        for (JsonObject line : lines) {
            if (!"ae2_snapshot".equals(
                line.get("t")
                    .getAsString())) {
                continue;
            }
            final JsonObject data = line.getAsJsonObject("data");
            final File file = new File(
                session.snapshotDir(),
                data.get("file")
                    .getAsString());
            try (java.io.InputStream in = new java.util.zip.GZIPInputStream(Files.newInputStream(file.toPath()))) {
                final JsonObject snapshot = new JsonParser()
                    .parse(new java.io.InputStreamReader(in, StandardCharsets.UTF_8))
                    .getAsJsonObject();
                for (com.google.gson.JsonElement e : snapshot.getAsJsonArray("items")) {
                    final JsonObject item = e.getAsJsonObject();
                    if (AE2_CRAFTABLE_KEY.equals(
                        item.get("key")
                            .getAsString())) {
                        final boolean ok = item.get("count")
                            .getAsLong() == 0 && item.has("craftable")
                            && data.get("craftableItems")
                                .getAsInt() >= 1;
                        check("ae2 census flags the pattern output craftable", ok, item + " line=" + data);
                        return;
                    }
                }
                seen.add(file.getName() + " craftableItems=" + data.get("craftableItems"));
            } catch (Exception e) {
                seen.add(file.getName() + " unreadable: " + e);
            }
        }
        check("ae2 census flags the pattern output craftable", false, AE2_CRAFTABLE_KEY + " absent from " + seen);
    }

    private static void place(EventBus bus, WorldServer world, EntityPlayerMP player, int x, int y, int z,
        Block block) {
        place(bus, world, player, x, y, z, block, 0);
    }

    /** In Forge's order: snapshot of the block being replaced, then the placement, then the event. */
    private static void place(EventBus bus, WorldServer world, EntityPlayerMP player, int x, int y, int z, Block block,
        int meta) {
        final BlockSnapshot replaced = BlockSnapshot.getBlockSnapshot(world, x, y, z);
        world.setBlock(x, y, z, block, meta, 3);
        bus.post(new BlockEvent.PlaceEvent(replaced, Blocks.stone, player));
    }

    private static final String[] EXPECTED_TYPES = { "session_start", "player_join", "timing_started",
        "gamemode_change", "inv_snapshot", "key_item_first_seen", "command", "difficulty_change", "death",
        "achievement", "dim_first_visit", "key_item_craft", "nei_packet", "nei_cheat", "multiblock_formed",
        "gt_explosion", "nbt_edit", "ae2_census_failed", "block_placed", "player_leave", "dim_change", "creative_slot",
        "world_saving", "gamerule_change", "command_output", "item_id_table", "gt_powerfail", "blood_altar_tier",
        "trash_insert", "invsee_open", "invsee_click", "invsee_close", "trash_can_closed", "watched_block",
        "node_census", "bulk_placed", "creative_block" };

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
        final List<JsonObject> machinePlaced = lines.stream()
            .filter(
                line -> "machine_placed".equals(
                    line.get("t")
                        .getAsString()))
            .map(line -> line.getAsJsonObject("data"))
            .collect(java.util.stream.Collectors.toList());
        check(
            "machine_placed only for the machine, not the vanilla block",
            machinePlaced.size() == 1,
            machinePlaced.toString());
        check(
            "machine_placed by a tool: itemKey is the tool, machineId and machine are the machine",
            machinePlaced.size() == 1 && "minecraft:diamond_pickaxe@0".equals(
                machinePlaced.get(0)
                    .get("itemKey")
                    .getAsString())
                && machinePlaced.get(0)
                    .has("machineId")
                && machinePlaced.get(0)
                    .get("machineId")
                    .getAsInt() == 0
                && machinePlaced.get(0)
                    .has("machine")
                && new ItemStack(Blocks.iron_block).getDisplayName()
                    .equals(
                        machinePlaced.get(0)
                            .get("machine")
                            .getAsString()),
            machinePlaced.toString());
        check(
            "block_placed only for tracked block ×2",
            counts.getOrDefault("block_placed", 0) == 2,
            "count=" + counts.getOrDefault("block_placed", 0));
        // only the step-2 stained glass: every later placement and break is in survival
        final List<String> creative = lines.stream()
            .filter(
                l -> "creative_block".equals(
                    l.get("t")
                        .getAsString()))
            .map(l -> {
                final JsonObject d = l.getAsJsonObject("data");
                return d.get("action")
                    .getAsString() + " "
                    + d.get("block")
                        .getAsString()
                    + "@"
                    + d.get("meta")
                        .getAsInt()
                    + " "
                    + d.get("name")
                        .getAsString();
            })
            .collect(java.util.stream.Collectors.toList());
        check(
            "creative_block: the creative place and break, nothing in survival",
            creative.equals(
                java.util.Arrays.asList(
                    "place minecraft:stained_glass@5 SelfTestAlice",
                    "break minecraft:stained_glass@5 SelfTestAlice")),
            String.valueOf(creative));
        check("both players joined", counts.getOrDefault("player_join", 0) >= 2, null);
        check("gamemode changes ×2", counts.getOrDefault("gamemode_change", 0) >= 2, null);
        check("difficulty changes ×2", counts.getOrDefault("difficulty_change", 0) >= 2, null);
        // the scenario's sink-level formations; on a real pack the TecTech fixture adds its own, checked below
        final long selfTestFormations = lines.stream()
            .filter(
                line -> "multiblock_formed".equals(
                    line.get("t")
                        .getAsString())
                    && line.getAsJsonObject("data")
                        .get("class")
                        .getAsString()
                        .startsWith("SelfTest"))
            .count();
        check(
            "multiblock dedupe, hatch-tier and coil-tier changes re-log, formation marker",
            selfTestFormations == 8,
            "count=" + selfTestFormations);
        if (Compat.GT5.isLoaded()) {
            final long ttFormations = lines.stream()
                .filter(
                    line -> "multiblock_formed".equals(
                        line.get("t")
                            .getAsString())
                        && "MTEActiveTransformer".equals(
                            line.getAsJsonObject("data")
                                .get("class")
                                .getAsString())
                        && line.getAsJsonObject("data")
                            .get("x")
                            .getAsInt() == ox(8)
                        && line.getAsJsonObject("data")
                            .get("z")
                            .getAsInt() == oz(8))
                .count();
            check("tectech multiblock formed on its own tick check, once", ttFormations == 1, "count=" + ttFormations);
            final JsonObject tt = lines.stream()
                .filter(
                    line -> "multiblock_formed".equals(
                        line.get("t")
                            .getAsString())
                        && "MTEActiveTransformer".equals(
                            line.getAsJsonObject("data")
                                .get("class")
                                .getAsString()))
                .map(line -> line.getAsJsonObject("data"))
                .findFirst()
                .orElse(null);
            checkTecTechFootprint(tt, lines);
            check(
                "tectech multi-amp hatch read: EV at 4 amps",
                tt != null && tt.has("energyTier")
                    && "EV".equals(
                        tt.get("energyTier")
                            .getAsString())
                    && tt.has("energyAmps")
                    && tt.get("energyAmps")
                        .getAsLong() == 4,
                String.valueOf(tt));
        }
        final List<JsonObject> bulk = lines.stream()
            .filter(
                line -> "bulk_placed".equals(
                    line.get("t")
                        .getAsString()))
            .map(line -> line.getAsJsonObject("data"))
            .collect(java.util.stream.Collectors.toList());
        check(
            "manipulator: one bulk_placed, the placed spot listed, the existing machine counted",
            bulk.size() == 1 && bulk.get(0)
                .getAsJsonArray("machines")
                .toString()
                .equals("[[" + ox(20) + "," + oy(0) + "," + oz(0) + ",0]]")
                && bulk.get(0)
                    .getAsJsonArray("pipes")
                    .size() == 0
                && bulk.get(0)
                    .get("checked")
                    .getAsInt() == 1
                && bulk.get(0)
                    .getAsJsonObject("names")
                    .has("0"),
            bulk.toString());
        check(
            "gamerule_change off then on",
            counts.getOrDefault("gamerule_change", 0) == 2,
            "count=" + counts.getOrDefault("gamerule_change", 0));
        check(
            "command_output only for allowlisted commands",
            counts.getOrDefault("command_output", 0) == 2 && lines.stream()
                .filter(
                    line -> "command_output".equals(
                        line.get("t")
                            .getAsString()))
                .allMatch(
                    line -> "gamerule".equals(
                        line.getAsJsonObject("data")
                            .get("cmd")
                            .getAsString())
                        && line.getAsJsonObject("data")
                            .getAsJsonArray("lines")
                            .size() > 0),
            "count=" + counts.getOrDefault("command_output", 0));
        check(
            "watched_block place ×2 + break",
            counts.getOrDefault("watched_block", 0) == 3,
            "count=" + counts.getOrDefault("watched_block", 0));
        final java.util.Set<String> outcomes = new java.util.HashSet<>();
        for (JsonObject line : lines) {
            if ("invsee_close".equals(
                line.get("t")
                    .getAsString())) {
                outcomes.add(
                    line.getAsJsonObject("data")
                        .get("outcome")
                        .getAsString());
            }
        }
        check(
            "invsee view outcomes",
            outcomes.containsAll(
                java.util.Arrays.asList(
                    "changes lost (target online at close)",
                    "saved, last writer wins",
                    "changes lost (view not closed)"))
                && counts.getOrDefault("invsee_close", 0) == 5,
            "outcomes=" + outcomes + " closes=" + counts.getOrDefault("invsee_close", 0));
        check(
            "trash_can_closed carries the chest's contents",
            lines.stream()
                .anyMatch(
                    line -> "trash_can_closed".equals(
                        line.get("t")
                            .getAsString())
                        && line.getAsJsonObject("data")
                            .getAsJsonArray("items")
                            .size() == 1),
            null);
        check(
            "node_census found the watched tile",
            lines.stream()
                .anyMatch(
                    line -> "node_census".equals(
                        line.get("t")
                            .getAsString())
                        && line.getAsJsonObject("data")
                            .get("nodes")
                            .getAsInt() >= 1),
            null);
        check(
            "world_saving off line carries lastSave",
            lines.stream()
                .anyMatch(
                    line -> "world_saving".equals(
                        line.get("t")
                            .getAsString())
                        && line.getAsJsonObject("data")
                            .has("lastSave")),
            null);
        check(
            "creative_slot unchanged slot not logged",
            counts.getOrDefault("creative_slot", 0) == 1,
            "count=" + counts.getOrDefault("creative_slot", 0));
        final List<JsonObject> savingLines = lines.stream()
            .filter(
                line -> "world_saving".equals(
                    line.get("t")
                        .getAsString()))
            .collect(java.util.stream.Collectors.toList());
        check(
            "world_saving off then on",
            savingLines.size() == 2 && !savingLines.get(0)
                .getAsJsonObject("data")
                .get("enabled")
                .getAsBoolean()
                && savingLines.get(1)
                    .getAsJsonObject("data")
                    .get("enabled")
                    .getAsBoolean(),
            "count=" + savingLines.size());
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
        check(
            "nbt_edit carries the before state",
            lines.stream()
                .anyMatch(
                    line -> "nbt_edit".equals(
                        line.get("t")
                            .getAsString())
                        && line.getAsJsonObject("data")
                            .has("beforeB64")
                        && "cafebabe".equals(
                            line.getAsJsonObject("data")
                                .get("beforeHash")
                                .getAsString())),
            null);
        if (nbtEditHashes == null) {
            check("nbt_edit mixin drill", true, "skipped: ServerUtilities not loaded");
        } else {
            check(
                "nbt_edit mixin drill: before and after hashes",
                lines.stream()
                    .anyMatch(
                        line -> "nbt_edit".equals(
                            line.get("t")
                                .getAsString())
                            && ("block " + ox(5) + "," + oy(0) + "," + oz(0)).equals(
                                line.getAsJsonObject("data")
                                    .get("target")
                                    .getAsString())
                            && line.getAsJsonObject("data")
                                .has("beforeHash")
                            && nbtEditHashes[0].equals(
                                line.getAsJsonObject("data")
                                    .get("beforeHash")
                                    .getAsString())
                            && nbtEditHashes[1].equals(
                                line.getAsJsonObject("data")
                                    .get("nbtHash")
                                    .getAsString())),
                "expected before=" + nbtEditHashes[0] + " after=" + nbtEditHashes[1]);
            final ItemStack edited = ((net.minecraft.tileentity.TileEntityChest) server.worldServers[0]
                .getTileEntity(ox(5), oy(0), oz(0))).getStackInSlot(0);
            check(
                "nbt_edit mixin drill: edit applied",
                edited != null && edited.stackSize == 2,
                edited == null ? "slot empty" : "count=" + edited.stackSize);
        }
        // Conditional rather than in EXPECTED_TYPES: without AE2 there is no census to assert on, and the
        // jar is meant to run on servers that do not have it.
        checkMixinsApplied();
        if (Compat.GT5.isLoaded()) {
            checkStructureTierPlans();
            checkStructureTiersOnThisSide(lines);
        }
        if (Compat.BETTER_QUESTING.isLoaded()) {
            check("event logged: quest_db", counts.getOrDefault("quest_db", 0) > 0, null);
        }
        if (com.gtnhspeedrun.audit.snapshot.StorageCensus.anythingToCensus()) {
            check("event logged: storage_census", counts.getOrDefault("storage_census", 0) > 0, null);
        }
        if (Compat.GT5.isLoaded()) {
            final JsonObject power = lines.stream()
                .filter(
                    line -> "power_census".equals(
                        line.get("t")
                            .getAsString()))
                .map(line -> line.getAsJsonObject("data"))
                .findFirst()
                .orElse(null);
            check(
                "power census read the LSC and wireless EU",
                power != null && power.get("lscs")
                    .getAsInt() >= 1
                    && power.get("readErrors")
                        .getAsInt() == 0
                    && new java.math.BigInteger(
                        power.get("wirelessStored")
                            .getAsString()).compareTo(java.math.BigInteger.valueOf(SEEDED_WIRELESS_EU))
                        >= 0,
                String.valueOf(power));
        }
        if (Compat.AE2.isLoaded()) {
            check(
                "event logged: ae2_snapshot",
                counts.getOrDefault("ae2_snapshot", 0) > 0,
                "count=" + counts.getOrDefault("ae2_snapshot", 0));
            checkAe2Craftable(lines);
        }
        if (Compat.WORLDEDIT.isLoaded()) {
            checkWorldEdit(lines);
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

    /**
     * The Active Transformer's formation line has the footprint: a 3×3×3 box holding the controller and the hatch,
     * and the facing the live controller had. The forced re-check at step 30 logs no footprint line, and the
     * rotation at step 31 logs exactly one, with the new rotation and the same box.
     */
    private void checkTecTechFootprint(JsonObject tt, List<JsonObject> lines) {
        final int[] box = new int[6];
        final boolean hasBox = tt != null && tt.has("bounds")
            && tt.getAsJsonArray("bounds")
                .size() == 6;
        if (hasBox) {
            for (int i = 0; i < 6; i++) {
                box[i] = tt.getAsJsonArray("bounds")
                    .get(i)
                    .getAsInt();
            }
        }
        final int[] hatch = com.gtnhspeedrun.audit.compat.TecTechFixture.hatchPosition;
        check(
            "tectech footprint: 3x3x3 box around the controller and the hatch",
            hasBox && box[3] - box[0] == 2
                && box[4] - box[1] == 2
                && box[5] - box[2] == 2
                && boxHolds(box, ox(8), oy(0), oz(8))
                && hatch != null
                && boxHolds(box, hatch[0], hatch[1], hatch[2]),
            tt == null ? "no formation line"
                : String.valueOf(tt.get("bounds")) + " hatch=" + java.util.Arrays.toString(hatch));
        check(
            "tectech footprint: facing, rotation and flip of the live controller",
            tt != null && ttFacing != null
                && tt.has("facing")
                && ttFacing[0].equals(
                    tt.get("facing")
                        .getAsString())
                && tt.has("rotation")
                && ttFacing[1].equals(
                    tt.get("rotation")
                        .getAsString())
                && tt.has("flip")
                && ttFacing[2].equals(
                    tt.get("flip")
                        .getAsString()),
            "line=" + tt + " live=" + java.util.Arrays.toString(ttFacing));
        final List<JsonObject> footprints = lines.stream()
            .filter(
                line -> "multiblock_footprint".equals(
                    line.get("t")
                        .getAsString())
                    && "MTEActiveTransformer".equals(
                        line.getAsJsonObject("data")
                            .get("class")
                            .getAsString()))
            .map(line -> line.getAsJsonObject("data"))
            .collect(java.util.stream.Collectors.toList());
        check(
            "tectech footprint line only for the rotation, with the new rotation and the same box",
            footprints.size() == 1 && "CLOCKWISE".equals(
                footprints.get(0)
                    .get("rotation")
                    .getAsString())
                && tt != null
                && String.valueOf(tt.get("bounds"))
                    .equals(
                        String.valueOf(
                            footprints.get(0)
                                .get("bounds"))),
            footprints.toString());
    }

    private static boolean boxHolds(int[] box, int x, int y, int z) {
        return x >= box[0] && y >= box[1] && z >= box[2] && x <= box[3] && y <= box[4] && z <= box[5];
    }

    /**
     * Mixins apply when their target class loads, and a scenario never touches some targets (the trash slot, the
     * /inv view container, BetterQuesting's edit handlers). A bad target would then crash a real game later, not
     * this boot. So every target of an installed mod is loaded here, and must show an injected audit$ member.
     */
    private void checkMixinsApplied() {
        final String[][] targets = { { null, "net.minecraft.command.CommandHandler" },
            { null, "net.minecraft.entity.player.EntityPlayerMP" }, { null, "net.minecraft.server.MinecraftServer" },
            { null, "net.minecraft.network.rcon.RConConsoleSource" },
            { null, "net.minecraft.network.NetHandlerPlayServer" },
            { "NotEnoughItems", "codechicken.nei.NEIServerUtils" }, { "NotEnoughItems", "codechicken.nei.NEISPH" },
            { "gregtech", "gregtech.common.data.GTPowerfailTracker" },
            { "gregtech", "gregtech.api.metatileentity.implementations.MTEMultiBlockBase" },
            { "gregtech", "tectech.thing.metaTileEntity.multi.base.TTMultiblockBase" },
            { "structurelib", "com.gtnewhorizon.structurelib.structure.StructureUtility" },
            { "betterquesting", "betterquesting.api2.utils.ParticipantInfo" },
            { "betterquesting", "bq_standard.tasks.base.TaskBase" },
            { "betterquesting", "betterquesting.network.handlers.NetQuestEdit" },
            { "betterquesting", "betterquesting.network.handlers.NetChapterEdit" },
            { "betterquesting", "betterquesting.network.handlers.NetImport" },
            { "betterquesting", "betterquesting.network.handlers.NetSettingSync" },
            { "AWWayofTime", "WayofTime.alchemicalWizardry.common.tileEntity.TEAltar" },
            { "ExtraUtilities", "com.rwtema.extrautils.gui.SlotTrash" },
            { "serverutilities", "serverutils.invsee.InvseeContainer" },
            { "serverutilities", "serverutils.net.MessageEditNBTResponse" } };
        for (String[] target : targets) {
            if (target[0] != null && !cpw.mods.fml.common.Loader.isModLoaded(target[0])) {
                continue;
            }
            try {
                final Class<?> c = Class.forName(target[1], true, SelfTest.class.getClassLoader());
                boolean injected = false;
                for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
                    injected |= m.getName()
                        .contains("audit$");
                }
                for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                    injected |= f.getName()
                        .contains("audit$");
                }
                check("mixin applied: " + target[1], injected, injected ? null : "no audit$ member");
            } catch (Throwable t) {
                check("mixin applied: " + target[1], false, String.valueOf(t));
            }
        }
    }

    /**
     * The tier fields StructureTiers finds on real controller classes, so a renamed GT field shows up here rather
     * than as a silently missing tier in a run's log. Classes are loaded without running their static initialisers.
     */
    private void checkStructureTierPlans() {
        final String[][] expected = { { "gtnhintergalactic.tile.multi.elevator.TileEntitySpaceElevator", "motorTier" },
            { "gtnhintergalactic.tile.multi.elevatormodules.TileEntityModuleMiner$TileEntityModuleMinerT2", "tTier",
                "tModuleTier" },
            { "tectech.thing.metaTileEntity.multi.MTEEyeOfHarmony", "spacetimeCompressionFieldMetadata",
                "timeAccelerationFieldMetadata", "stabilisationFieldMetadata" },
            { "kubatech.tileentity.gregtech.multiblock.MTEDEFusionCrafter", "mTierCasing", "mFusionTierCasing" },
            { "gregtech.common.tileentities.machines.multi.foundry.MTEExoFoundry", "foundryData.tier",
                "foundryData.modules" },
            { "gregtech.common.tileentities.machines.multi.MTEPlasmaForge", "convergence", "mCoilLevel" },
            { "gtPlusPlus.xmod.gregtech.common.tileentities.machines.multi.production.MTEQuantumForceTransformer",
                "mCraftingTier", "mFocusingTier" },
            { "gregtech.common.tileentities.machines.multi.MTENanoForge", "mSpecialTier" } };
        for (String[] row : expected) {
            try {
                final List<String> keys = com.gtnhspeedrun.audit.trackers.StructureTiers
                    .planKeys(Class.forName(row[0], false, SelfTest.class.getClassLoader()));
                boolean all = true;
                for (int i = 1; i < row.length; i++) {
                    all &= keys.contains(row[i]);
                }
                check(
                    "structure tiers planned: " + row[0].substring(row[0].lastIndexOf('.') + 1),
                    all,
                    keys.toString());
            } catch (Throwable t) {
                check("structure tiers planned: " + row[0], false, String.valueOf(t));
            }
        }
    }

    /**
     * Every registered multiblock's tiers read without a throw on this side, and the Extreme Entity Crusher placed at
     * step 3 logged one formation. The detail names the classes whose own fields this side cannot list.
     */
    private void checkStructureTiersOnThisSide(List<JsonObject> lines) {
        final List<String> failed = com.gtnhspeedrun.audit.compat.FormationSideCheck.readEveryMultiblock();
        check(
            "structure tiers read on every multiblock",
            failed.isEmpty(),
            failed.isEmpty()
                ? "fields not listable on this side: "
                    + com.gtnhspeedrun.audit.compat.FormationSideCheck.unlistableMultiblocks()
                : failed.toString());
        final long crusherFormations = lines.stream()
            .filter(
                line -> "multiblock_formed".equals(
                    line.get("t")
                        .getAsString())
                    && "MTEExtremeEntityCrusher".equals(
                        line.getAsJsonObject("data")
                            .get("class")
                            .getAsString())
                    && line.getAsJsonObject("data")
                        .get("x")
                        .getAsInt() == ox(-6)
                    && line.getAsJsonObject("data")
                        .get("z")
                        .getAsInt() == oz(-6))
            .count();
        check("extreme entity crusher formation logged once", crusherFormations == 1, "count=" + crusherFormations);
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
