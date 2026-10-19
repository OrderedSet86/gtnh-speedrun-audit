package com.gtnhspeedrun.audit.selftest;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;

import net.minecraft.block.Block;
import net.minecraft.entity.monster.EntityZombie;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.inventory.ContainerChest;
import net.minecraft.inventory.InventoryBasic;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.network.NetworkManager;
import net.minecraft.server.MinecraftServer;
import net.minecraft.stats.AchievementList;
import net.minecraft.tileentity.TileEntityChest;
import net.minecraft.util.ChunkCoordinates;
import net.minecraft.util.DamageSource;
import net.minecraft.util.EntityDamageSource;
import net.minecraft.util.StatCollector;
import net.minecraft.world.EnumDifficulty;
import net.minecraft.world.MinecraftException;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.WorldSettings;
import net.minecraftforge.common.util.BlockSnapshot;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.AchievementEvent;
import net.minecraftforge.event.world.BlockEvent;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.gtnhspeedrun.audit.GtnhSpeedrunAudit;
import com.gtnhspeedrun.audit.compat.Ae2Fixture;
import com.gtnhspeedrun.audit.compat.Compat;
import com.gtnhspeedrun.audit.compat.FormationSideCheck;
import com.gtnhspeedrun.audit.compat.NbtEditDrill;
import com.gtnhspeedrun.audit.compat.TecTechFixture;
import com.gtnhspeedrun.audit.compat.WorldEditFixture;
import com.gtnhspeedrun.audit.compat.WorldPos;
import com.gtnhspeedrun.audit.core.JsonUtil;
import com.gtnhspeedrun.audit.core.SessionManager;
import com.gtnhspeedrun.audit.core.Verifier;
import com.gtnhspeedrun.audit.snapshot.StorageCensus;
import com.gtnhspeedrun.audit.trackers.AuditSinks;
import com.gtnhspeedrun.audit.trackers.FormationGuard;
import com.gtnhspeedrun.audit.trackers.GtFormationLogger;
import com.gtnhspeedrun.audit.trackers.GtMachineSites;
import com.gtnhspeedrun.audit.trackers.InvseeViews;
import com.gtnhspeedrun.audit.trackers.MachineSite;
import com.gtnhspeedrun.audit.trackers.MultiblockPower;
import com.gtnhspeedrun.audit.trackers.StructureTiers;
import com.mojang.authlib.GameProfile;

import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.eventhandler.EventBus;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/**
 * Run every loggable action with fake players on a live dev dedicated server, then read the JSONL log back and check
 * that the trackers logged each action. The harness runs only with {@code -Dspeedrunaudit.selftest=<resultDir>} set.
 * The drill script adds tests of the verdict across several boots.
 *
 * <p>
 * The run clocks, the movement detection and the gamemode and difficulty polls run on server ticks, so the server
 * has to tick between steps. The harness is a step machine on ServerTickEvent, with {@link #STEP_GAP_TICKS} ticks
 * between steps. Two fake players drive the multiplayer paths. On a pack server, the scenario runs with the mixins
 * applied.
 *
 * <p>
 * The harness writes {@code <resultDir>/selftest.json} and one stdout line, SELFTEST PASSED or SELFTEST FAILED.
 * Scripts check the file and the line, because the server shuts down normally and the exit code is the same for a pass
 * and a fail.
 */
public final class SelfTest {

    public static final String PROPERTY = "speedrunaudit.selftest";
    // stand-ins for the GT machine block and the Matter Manipulator, so the drill runs without either mod
    public static final String STAND_IN_MACHINE_BLOCK = "minecraft:iron_block";
    public static final String STAND_IN_MANIPULATOR_PREFIX = "minecraft:stick@";
    /** GT MetaTileEntity id of the Lapotronic Supercapacitor (LSC) controller. */
    private static final int LSC_CONTROLLER_META = 13106;
    private static final long SEEDED_WIRELESS_EU = 12_345L;
    // nothing in the scenario stocks this item, so its census entry can come only from the pattern
    private static final String AE2_CRAFTABLE_KEY = "minecraft:nether_star@0";
    private static final List<String> EXPECTED_TYPES = List.of(
        "session_start",
        "player_join",
        "timing_started",
        "gamemode_change",
        "inv_snapshot",
        "key_item_first_seen",
        "command",
        "difficulty_change",
        "death",
        "achievement",
        "dim_first_visit",
        "key_item_craft",
        "nei_packet",
        "nei_cheat",
        "multiblock_formed",
        "gt_explosion",
        "nbt_edit",
        "ae2_census_failed",
        "multiblock_log_failed",
        "block_placed",
        "player_leave",
        "dim_change",
        "creative_slot",
        "world_saving",
        "gamerule_change",
        "command_output",
        "item_id_table",
        "gt_powerfail",
        "blood_altar_tier",
        "trash_insert",
        "invsee_open",
        "invsee_click",
        "invsee_close",
        "trash_can_closed",
        "watched_block",
        "node_census",
        "bulk_placed",
        "creative_block");
    /** Every per-tick poll runs within this gap, so each poll reads the change of the step before. */
    private static final int STEP_GAP_TICKS = 5;

    private final MinecraftServer server;
    private final SessionManager session;
    private final Path resultDir;
    /**
     * The fake players' events go to the audit listeners only. On the global buses, every mod of a real pack would get
     * the events. A packet from a login handler then makes the outbound dispatch of FML throw a NullPointerException,
     * because a fake player has no netty channel. The angel ring of Extra Utilities sends such a packet, first seen on
     * pack 2.9.0-RC-1.
     */
    private final EventBus bus = new EventBus();
    private final List<Check> checks = new ArrayList<>();

    private EntityPlayerMP alice;
    private EntityPlayerMP bob;
    private int tick;
    private int step;
    private boolean done;
    private long onlineTicksAtLogout = -1;
    private long ticksAtLogout = -1;
    /** The Active Transformer's live {direction, rotation, flip}, read at step 30. */
    private String[] tecTechFacing;
    /** The before and after hashes of the live /nbtedit drill, or null without ServerUtilities. */
    private String[] nbtEditHashes;
    /**
     * The spot that every fixture block is placed relative to, above the world spawn and in the spawn chunks. The
     * spawn chunks generate at startup and stay loaded with nobody online. Fixed coordinates would make the result
     * depend on the seed. The chunk-generation throttle of Hodgepodge returns an empty chunk for a chunk never
     * generated, and a setBlock() into that chunk is dropped with no error. The spawn chunks are also loaded after a
     * restart, so the structure check runs then, and the restart drill can check that no new line is logged for the
     * TecTech fixture.
     */
    private ChunkCoordinates origin;

    /** Register {@code auditListeners}, the Forge and FML bus listeners of the audit, on the fake players' bus. */
    public SelfTest(SessionManager session, Path resultDir, List<Object> auditListeners) {
        this.server = session.server();
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
        if (tick % STEP_GAP_TICKS != 0) {
            return;
        }
        try {
            runStep(step++);
        } catch (RuntimeException | LinkageError e) { // noqa: NAR142, degrade and report over pack mod handlers
            // The check detail is one line. The stack trace in the log has the class of the mod handler that
            // threw.
            GtnhSpeedrunAudit.LOG.error("[selftest] step {} threw", step - 1, e);
            check("step " + (step - 1) + " threw", false, String.valueOf(e));
            finish();
        }
    }

    /**
     * Run step {@code stepNumber} of the scenario. A step number with no case does nothing, so the scenario waits
     * for one step.
     */
    @SuppressWarnings("unchecked")
    private void runStep(int stepNumber) {
        final WorldServer overworld = server.worldServers[0];
        switch (stepNumber) {
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
                alice.setPositionAndRotation(offsetX(0), offsetY(1), offsetZ(0), 0, 0);
                bob.setPositionAndRotation(offsetX(0), offsetY(1), offsetZ(0), 0, 0);
                // Vanilla sends broadcasts such as the time sync to the net handler of every listed player each
                // tick, so a fake player needs a real handler. The NetworkManager here has no channel. The flush of
                // NetworkManager checks for a channel, so the packets stay in the queue and go nowhere.
                new NetHandlerPlayServer(server, new NetworkManager(false), alice);
                new NetHandlerPlayServer(server, new NetworkManager(false), bob);
                server.getConfigurationManager().playerEntityList.add(alice);
                server.getConfigurationManager().playerEntityList.add(bob);
                bus.post(new PlayerEvent.PlayerLoggedInEvent(alice));
                bus.post(new PlayerEvent.PlayerLoggedInEvent(bob));
            }
            case 1 -> // the first movement, a stand-in for WASD input: one horizontal step between polls
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
                // creative_block: a block that is neither tracked nor watched, placed and broken in creative.
                // The glass has meta 5 and the air that the glass replaces has meta 0, so the check fails if the
                // tracker
                // reads the meta from the snapshot of the replaced block and not from the world.
                place(offset(5, 0, 0), Blocks.stained_glass, 5);
                bus.post(
                    new BlockEvent.BreakEvent(
                        offsetX(5),
                        offsetY(0),
                        offsetZ(0),
                        overworld,
                        Blocks.stained_glass,
                        5,
                        alice));
                // the self-test node census counts chests as stand-ins for Thaumcraft nodes
                overworld.setBlock(offsetX(12), offsetY(7), offsetZ(12), Blocks.chest);
                // Save the world before the saving-off change of step 5, so the world_saving line has lastSave.
                // The save comes one step before step 3 places the AE2 grid host. A save in the tick of the
                // placement stopped the grid from forming.
                try {
                    overworld.saveAllChunks(false, null);
                } catch (MinecraftException e) {
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
                    final String problem = WorldEditFixture.edit(fixturePos(overworld, offset(20, 17, 20)));
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
                // a ServerUtilities backup and /save-off set this flag
                overworld.levelSaving = true;
            }
            case 6 -> {
                overworld.difficultySetting = EnumDifficulty.NORMAL;
                overworld.levelSaving = false;
                server.getCommandManager()
                    .executeCommand(server, "gamerule doFireTick true");
                // /trash_can's chest: open the chest with something inside, then close the chest next step
                final InventoryBasic trash = new InventoryBasic(
                    StatCollector.translateToLocal("key.serverutilities.trash"),
                    true,
                    36);
                trash.setInventorySlotContents(0, new ItemStack(Items.diamond, 5));
                alice.openContainer = new ContainerChest(alice.inventory, trash);
                bus.post(new TickEvent.PlayerTickEvent(TickEvent.Phase.END, alice));
            }
            case 7 -> {
                // FakePlayer is invulnerable and its onDeath() does nothing, so damage never fires
                // LivingDeathEvent. Post the event here. The death tracker subscribes to LivingDeathEvent, so the
                // tracker's handler runs the same code path for this event and for a real death.
                bus.post(new LivingDeathEvent(bob, DamageSource.outOfWorld));
                // a mob kill: the death line has the killer
                bus.post(new LivingDeathEvent(bob, new EntityDamageSource("mob", new EntityZombie(overworld))));
                // openInventory has no parent, so openInventory is logged. The parent of mineWood is openInventory, and
                // a posted event unlocks nothing, so mineWood stays locked and is not logged
                bus.post(new AchievementEvent(alice, AchievementList.openInventory));
                bus.post(new AchievementEvent(alice, AchievementList.mineWood));
                bus.post(new PlayerEvent.PlayerChangedDimensionEvent(alice, 0, -1));
                bus.post(new PlayerEvent.ItemCraftedEvent(alice, new ItemStack(Items.diamond, 9), alice.inventory));
                alice.openContainer = alice.inventoryContainer; // closes the trash chest
                bus.post(new TickEvent.PlayerTickEvent(TickEvent.Phase.END, alice));
            }
            case 8 -> {
                // Forge posts PlaceEvent after setBlock(), and Forge reads placedBlock for the event back from the
                // world. So each block goes in first. A PlaceEvent over air would make both checks below pass
                // whatever the trackers do.
                // Negative case: no machine_placed and no block_placed line is logged for an untracked vanilla
                // block.
                alice.inventory.setInventorySlotContents(alice.inventory.currentItem, new ItemStack(Blocks.dirt));
                place(offset(0, 0, 0), Blocks.dirt);
                // Positive case: diamond_block is in the trackedPlacements and watchedBlocks of the self-test
                // config, as a stand-in for the Stargate blocks. Each of the two placements is expected to log a
                // block_placed line.
                alice.inventory
                    .setInventorySlotContents(alice.inventory.currentItem, new ItemStack(Blocks.diamond_block));
                place(offset(1, 0, 0), Blocks.diamond_block);
                place(offset(2, 0, 0), Blocks.diamond_block);
                // watched_block break of a diamond_block
                bus.post(
                    new BlockEvent.BreakEvent(
                        offsetX(2),
                        offsetY(0),
                        offsetZ(0),
                        overworld,
                        Blocks.diamond_block,
                        0,
                        alice));
                driveManipulator(overworld);
                // A machine placed with a tool in the main hand, such as a Vajra or a StructureLib projector on
                // the pack. The machine_placed line has the tool as itemKey and the machine from the world.
                alice.inventory
                    .setInventorySlotContents(alice.inventory.currentItem, new ItemStack(Items.diamond_pickaxe));
                place(offset(3, 0, 0), Blocks.iron_block);

                // the sink layer below the mixins. The mixins bind only on a pack server
                AuditSinks.logNeiPacket(toUuidString(alice), "SelfTestAlice", 1);
                AuditSinks.NeiCheat.of("give")
                    .player(alice)
                    .item("minecraft:diamond@0", 64)
                    .log();
                final MachineSite multi = new MachineSite("SelfTestMulti", "test.multi", 0, 1, 2, 3);
                AuditSinks.logMultiblockFormed("gt", multi);
                AuditSinks.logMultiblockFormed("gt", multi); // dedupe
                // a creative tab take is logged. A packet that leaves the slot unchanged is not
                final ItemStack creativeDiamonds = new ItemStack(Items.diamond, 64);
                AuditSinks.logCreativeSlot(alice, new AuditSinks.SlotChange(36, creativeDiamonds, null), true);
                AuditSinks.logCreativeSlot(
                    alice,
                    new AuditSinks.SlotChange(36, creativeDiamonds, creativeDiamonds.copy()),
                    true);
                // hatch tiers are in the dedupe key: LV is logged, LV again is deduped, the UV upgrade is logged
                final MultiblockPower lv = MultiblockPower.ofEnergyHatches(2, "LV");
                final MultiblockPower uv = MultiblockPower.ofEnergyHatches(2, "UV");
                final MachineSite hatched = new MachineSite("SelfTestMulti", "test.multi", 0, 7, 8, 9);
                AuditSinks.logMultiblockFormed("gt", hatched, lv);
                AuditSinks.logMultiblockFormed("gt", hatched, lv);
                AuditSinks.logMultiblockFormed("gt", hatched, uv);
                final MachineSite machine = new MachineSite("SelfTestMachine", "test.machine", 0, 4, 5, 6);
                AuditSinks.logGtExplosion(machine, 42);
                // coil and structure tiers are in the dedupe key too: LV is logged, LV again is deduped, HV is logged
                final MultiblockPower coilLv = MultiblockPower
                    .ofEnergyHatches(1, "EV", Collections.singletonMap("mCoilLevel", "LV"));
                final MultiblockPower coilHv = MultiblockPower
                    .ofEnergyHatches(1, "EV", Collections.singletonMap("mCoilLevel", "HV"));
                final MachineSite ebf = new MachineSite("SelfTestEbf", "test.ebf", 0, 10, 11, 12);
                AuditSinks.logMultiblockFormed("gt", ebf, coilLv);
                AuditSinks.logMultiblockFormed("gt", ebf, coilLv);
                AuditSinks.logMultiblockFormed("gt", ebf, coilHv);
                driveFormationMarker();
                AuditSinks.logGtPowerfail(machine);
                AuditSinks.logBloodAltarTier(new MachineSite("SelfTestAltar", null, 0, 20, 64, 20), 1, 2);
                AuditSinks.logTrashInsert(alice, null, new ItemStack(Items.diamond, 2));
                final JsonArray invseeChanges = new JsonArray();
                invseeChanges.add(new JsonObject());
                driveInvseeViews(overworld, invseeChanges);
                final JsonObject giveExtra = new JsonObject();
                giveExtra.addProperty("inserted", 0);
                AuditSinks.NeiCheat.of("give")
                    .player(alice)
                    .item("minecraft:diamond@0", 64)
                    .extra(giveExtra)
                    .log();
                final NBTTagCompound beforeEdit = new NBTTagCompound();
                beforeEdit.setInteger("charge", 5);
                AuditSinks.NbtEdit.of("player", "player SelfTestAlice")
                    .player(alice)
                    .written("deadbeef", null)
                    .before("cafebabe", JsonUtil.nbtToB64(beforeEdit))
                    .log();
                // the mixin itself, through the packet handler of ServerUtilities. The drill runs only on a pack
                // server
                nbtEditHashes = NbtEditDrill.editChest(alice, fixturePos(overworld, offset(5, 0, 0)));
                // A formation that cannot be logged: a controller whose formation logging throws, run twice
                // through FormationGuard, the guard of the mixins. The step must log one multiblock_log_failed
                // line and must not throw.
                final SelfTestBrokenController broken = new SelfTestBrokenController();
                final MachineSite brokenSite = new MachineSite(
                    SelfTestBrokenController.class.getSimpleName(),
                    null,
                    0,
                    offsetX(-8),
                    offsetY(0),
                    offsetZ(-8));
                FormationGuard.log(broken, false, brokenSite);
                FormationGuard.log(broken, false, brokenSite);
                // the real AE2 census fails only on API drift, so call the failure reporter here
                AuditSinks.announceAe2CensusFailure(
                    Collections.singletonList(
                        AuditSinks.logAe2CensusFailure(
                            new AuditSinks.Ae2CensusFailure(
                                0,
                                "grid",
                                "selftest",
                                new IllegalStateException("selftest")))));
                final Map<String, String> fakeMods = new HashMap<>();
                fakeMods.put("selftestmod", "1.0");
                final Object key = new Object();
                AuditSinks.storeClientModList(key, fakeMods);
                check("client modlist round-trip", fakeMods.equals(AuditSinks.readClientMods(key)), null);
            }
            case 9 -> {
                bus.post(new PlayerEvent.PlayerLoggedOutEvent(alice));
                bus.post(new PlayerEvent.PlayerLoggedOutEvent(bob));
                server.getConfigurationManager().playerEntityList.remove(alice);
                server.getConfigurationManager().playerEntityList.remove(bob);
                onlineTicksAtLogout = session.logger()
                    .clock()
                    .getOnline();
                ticksAtLogout = session.logger()
                    .clock()
                    .get();
            }
            // steps 10 to 29 wait for the TecTech start-up structure check. The check runs about 100 ticks after
            // step 3 places the controller
            case 30 -> recheckTecTechMulti(overworld);
            case 31 -> rotateTecTechMulti(overworld);
            case 34 -> {
                check(
                    "pticks frozen after logout",
                    session.logger()
                        .clock()
                        .getOnline() == onlineTicksAtLogout,
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

    private void check(String name, boolean ok, String detail) {
        checks.add(new Check(name, ok, detail == null ? "" : detail));
        final String detailSuffix = detail == null ? "" : " (" + detail + ")";
        GtnhSpeedrunAudit.LOG.info("[selftest] {} — {}{}", ok ? "ok  " : "FAIL", name, detailSuffix);
    }

    private int offsetX(int dx) {
        return readOrigin().posX + dx;
    }

    private int offsetY(int dy) {
        return readOrigin().posY + dy;
    }

    private int offsetZ(int dz) {
        return readOrigin().posZ + dz;
    }

    private ChunkCoordinates readOrigin() {
        final int ORIGIN_HEIGHT_ABOVE_SPAWN = 40;
        if (origin == null) {
            final ChunkCoordinates spawn = server.worldServers[0].getSpawnPoint();
            origin = new ChunkCoordinates(spawn.posX, spawn.posY + ORIGIN_HEIGHT_ABOVE_SPAWN, spawn.posZ);
        }
        return origin;
    }

    private void place(ChunkCoordinates spot, Block block) {
        place(spot, block, 0);
    }

    /** Place as alice in Forge's order: snapshot of the replaced block, then the placement, then the event. */
    private void place(ChunkCoordinates spot, Block block, int meta) {
        final int NOTIFY_AND_SEND_TO_CLIENTS = 3;
        final WorldServer world = server.worldServers[0];
        final BlockSnapshot replaced = BlockSnapshot.getBlockSnapshot(world, spot.posX, spot.posY, spot.posZ);
        world.setBlock(spot.posX, spot.posY, spot.posZ, block, meta, NOTIFY_AND_SEND_TO_CLIENTS);
        bus.post(new BlockEvent.PlaceEvent(replaced, Blocks.stone, alice));
    }

    private ChunkCoordinates offset(int dx, int dy, int dz) {
        return new ChunkCoordinates(offsetX(dx), offsetY(dy), offsetZ(dz));
    }

    private static WorldPos fixturePos(World world, ChunkCoordinates spot) {
        return new WorldPos(world, spot.posX, spot.posY, spot.posZ);
    }

    /**
     * Give the AE2 census a grid to count, so the test runs the ae2_snapshot code. The grid is an ME Interface with
     * one processing pattern for {@link #AE2_CRAFTABLE_KEY}, powered so the pattern is provided. The census then
     * writes the pattern output as a count-0 craftable entry. The AE2 types are in Ae2Fixture, so SelfTest loads on a
     * server without AE2.
     */
    private void placeAe2GridHost(WorldServer world) {
        if (!Compat.AE2.isLoaded()) {
            return;
        }
        final String problem = Ae2Fixture.build(
            fixturePos(world, offset(10, 7, 10)),
            new ItemStack(Blocks.cobblestone),
            new ItemStack(Items.nether_star));
        if (problem != null) {
            check("ae2 grid with a pattern built", false, problem);
        }
    }

    /**
     * Give the power census an LSC controller to read and a wireless EU balance. The method uses the registry name
     * and reflection, so SelfTest loads on a server without GregTech. The controller is unformed, so its capacitor
     * counts are empty, but the census reads every real field.
     */
    private void seedPowerCensus(WorldServer world, EntityPlayerMP player) {
        if (!Compat.GT5.isLoaded()) {
            return;
        }
        // the controller goes on top of this stone. Clear the spot above, whatever the terrain
        world.setBlock(offsetX(14), offsetY(6), offsetZ(10), Blocks.stone);
        world.setBlockToAir(offsetX(14), offsetY(7), offsetZ(10));
        final Object block = Block.blockRegistry.getObject("gregtech:gt.blockmachines");
        if (!(block instanceof Block machineBlock)) {
            check("gt machine block present", false, "gregtech:gt.blockmachines not registered");
            return;
        }
        new ItemStack(machineBlock, 1, LSC_CONTROLLER_META)
            .tryPlaceItemIntoWorld(player, world, offsetX(14), offsetY(6), offsetZ(10), 1, 0.5f, 1f, 0.5f);
        try {
            Class.forName("gregtech.common.misc.WirelessNetworkManager")
                .getMethod("addEUToGlobalEnergyMap", UUID.class, long.class)
                .invoke(null, player.getUniqueID(), SEEDED_WIRELESS_EU);
        } catch (ReflectiveOperationException | RuntimeException e) { // noqa: NAR142, reflection into GT wireless code
            check("wireless EU seeded", false, String.valueOf(e));
        }
    }

    /** Build an Active Transformer that forms only through the structure check in TTMultiblockBase.onPostTick(). */
    private void buildTecTechMulti(WorldServer world, EntityPlayerMP player) {
        if (!Compat.GT5.isLoaded()) {
            return;
        }
        final String problem = TecTechFixture.build(player, fixturePos(world, offset(8, 0, 8)));
        if (problem != null) {
            check("tectech multiblock built", false, problem);
        }
    }

    /**
     * Log an Extreme Entity Crusher's formation on this dedicated server, the way the structure-check hook logs a
     * formation. The crusher has a field of a client-only type. {@link #checkStructureTiersOnThisSide} checks the
     * formation line.
     */
    private void logCrusherFormation(WorldServer world, EntityPlayerMP player) {
        if (!Compat.GT5.isLoaded()) {
            return;
        }
        final String problem = FormationSideCheck.logCrusherFormation(player, fixturePos(world, offset(-6, 0, -6)));
        check("extreme entity crusher formation logged without a throw", problem == null, problem);
    }

    /**
     * Drive a Matter Manipulator build, with a stick for the manipulator and iron blocks for GT machines. The
     * manipulator fires PlaceEvent before placing, with the block already at the spot, and then places with no
     * event. Spot 20 gets a machine and is checked twice, spot 21 already has a machine, and spot 22 stays air. The
     * expected result is one bulk_placed line with one machine and one checked spot. Spots 20 and 22 are cleared
     * first, so terrain cannot stand in for a machine.
     */
    private void driveManipulator(WorldServer world) {
        alice.inventory.setInventorySlotContents(alice.inventory.currentItem, new ItemStack(Items.stick));
        world.setBlockToAir(offsetX(20), offsetY(0), offsetZ(0));
        world.setBlockToAir(offsetX(22), offsetY(0), offsetZ(0));
        world.setBlock(offsetX(21), offsetY(0), offsetZ(0), Blocks.iron_block);
        for (int x : new int[] { 20, 20, 21, 22 }) {
            bus.post(
                new BlockEvent.PlaceEvent(
                    BlockSnapshot.getBlockSnapshot(world, offsetX(x), offsetY(0), offsetZ(0)),
                    Blocks.stone,
                    alice));
        }
        world.setBlock(offsetX(20), offsetY(0), offsetZ(0), Blocks.iron_block);
    }

    private static String toUuidString(EntityPlayerMP player) {
        return player.getGameProfile()
            .getId()
            .toString();
    }

    /**
     * Drive the GT formation marker below the mixin. A line is logged on the first call and on a tier change. A line
     * is logged again when a backup restores a controller without the marker, and not when the marker comes back with
     * the chunk. The expected result is three lines.
     */
    private void driveFormationMarker() {
        final MachineSite site = new MachineSite("SelfTestMarked", "test.marked", 0, 13, 14, 15);
        final AuditSinks.GtFormation ev = new AuditSinks.GtFormation(
            site,
            MultiblockPower.ofEnergyHatches(1, "EV"),
            null);
        final AuditSinks.GtFormation iv = new AuditSinks.GtFormation(
            site,
            MultiblockPower.ofEnergyHatches(1, "IV"),
            null);
        final String marker = AuditSinks.logGtMultiblockFormed(null, ev);
        final String reloaded = AuditSinks.logGtMultiblockFormed(marker, ev);
        check("formation marker unchanged when nothing logged", marker != null && marker.equals(reloaded), marker);
        AuditSinks.logGtMultiblockFormed(marker, iv);
        AuditSinks.logGtMultiblockFormed(null, ev);
    }

    /**
     * Drive four /inv view outcomes through InvseeViews, below the mixin: a target who logs in mid-view, two views of
     * one offline target, a view replaced by another, and a viewer who leaves with a view open. The last outcome ends
     * at the logouts of step 9.
     */
    private void driveInvseeViews(WorldServer overworld, JsonArray changes) {
        final UUID carolId = UUID.nameUUIDFromBytes("selftest-carol".getBytes(StandardCharsets.UTF_8));
        final UUID daveId = UUID.nameUUIDFromBytes("selftest-dave".getBytes(StandardCharsets.UTF_8));
        final UUID aliceId = alice.getUniqueID();
        final InvseeViews.Party aliceParty = new InvseeViews.Party(aliceId, "SelfTestAlice");
        final InvseeViews.Party bobParty = new InvseeViews.Party(bob.getUniqueID(), "SelfTestBob");
        final InvseeViews.Party carolParty = new InvseeViews.Party(carolId, "SelfTestCarol");
        final InvseeViews.Party daveParty = new InvseeViews.Party(daveId, "SelfTestDave");
        // target logs in mid-view: the write-back is skipped
        final String joined = InvseeViews.open(aliceParty, carolParty, false);
        AuditSinks.InvseeClick.of(joined, alice)
            .target("SelfTestCarol", carolId.toString(), false)
            .click(3, 0, 0)
            .changes(changes)
            .log();
        InvseeViews.countClick(joined, true);
        final EntityPlayerMP carol = FakePlayerFactory.get(overworld, new GameProfile(carolId, "SelfTestCarol"));
        bus.post(new PlayerEvent.PlayerLoggedInEvent(carol));
        InvseeViews.end(joined, aliceParty, new InvseeViews.ViewEnd("closed", "SelfTestCarol", true));
        // two views of one offline target: last writer wins
        final String first = InvseeViews.open(aliceParty, daveParty, false);
        final String second = InvseeViews.open(bobParty, daveParty, false);
        InvseeViews.countClick(first, true);
        InvseeViews.countClick(second, true);
        InvseeViews.end(first, aliceParty, new InvseeViews.ViewEnd("closed", "SelfTestDave", false));
        InvseeViews.end(second, bobParty, new InvseeViews.ViewEnd("closed", "SelfTestDave", false));
        // replaced without a close
        final String replaced = InvseeViews.open(aliceParty, daveParty, false);
        InvseeViews.countClick(replaced, true);
        InvseeViews.end(replaced, aliceParty, new InvseeViews.ViewEnd("replaced", null, false));
        // left open: bob's logout at step 9 ends the view
        final String abandoned = InvseeViews.open(bobParty, daveParty, false);
        InvseeViews.countClick(abandoned, true);
    }

    /** Force a re-check of the formed Active Transformer and read its facing. The footprint is unchanged. */
    private void recheckTecTechMulti(WorldServer world) {
        if (!Compat.GT5.isLoaded()) {
            return;
        }
        final String problem = TecTechFixture.recheck(fixturePos(world, offset(8, 0, 8)));
        check("tectech multiblock re-checked", problem == null, problem);
        tecTechFacing = TecTechFixture.readFacing(fixturePos(world, offset(8, 0, 8)));
    }

    /**
     * Turn the formed Active Transformer a quarter turn, as a wrench does. The expected result is one
     * multiblock_footprint line with rotation CLOCKWISE.
     */
    private void rotateTecTechMulti(WorldServer world) {
        if (!Compat.GT5.isLoaded()) {
            return;
        }
        final String problem = TecTechFixture.rotateAndRecheck(fixturePos(world, offset(8, 0, 8)));
        check("tectech multiblock rotated and re-formed", problem == null, problem);
    }

    /**
     * Drain the log writer, read every JSONL file of the session, and check the logged events. A log that cannot be
     * read fails the "log readable" check and skips the other checks.
     */
    private void verifyLog() {
        final long DRAIN_TIMEOUT_MS = 10_000;
        session.logger()
            .writer()
            .drain(DRAIN_TIMEOUT_MS);
        final Map<String, Integer> counts = new HashMap<>();
        final List<JsonObject> lines = new ArrayList<>();
        try (DirectoryStream<Path> logFiles = Files.newDirectoryStream(
            session.logDir(),
            path -> path.getFileName()
                .toString()
                .endsWith(".jsonl"))) {
            for (Path logFile : logFiles) {
                for (String rawLine : Files.readAllLines(logFile, StandardCharsets.UTF_8)) {
                    if (!rawLine.isEmpty()) {
                        final JsonObject line = new JsonParser().parse(rawLine)
                            .getAsJsonObject();
                        lines.add(line);
                        counts.merge(
                            line.get("t")
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
        final List<JsonObject> machinePlaced = selectEventData(lines, "machine_placed");
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
        final List<String> achievements = selectEventData(lines, "achievement").stream()
            .map(
                data -> data.get("achId")
                    .getAsString())
            .collect(Collectors.toList());
        check(
            "achievement logged only when its parent is unlocked",
            achievements.equals(List.of("achievement.openInventory")),
            achievements.toString());
        // only the step-2 stained glass: every later placement and break is in survival
        final List<String> creative = selectEventData(lines, "creative_block").stream()
            .map(
                data -> data.get("action")
                    .getAsString() + " "
                    + data.get("block")
                        .getAsString()
                    + "@"
                    + data.get("meta")
                        .getAsInt()
                    + " "
                    + data.get("name")
                        .getAsString())
            .collect(Collectors.toList());
        check(
            "creative_block: the creative place and break, nothing in survival",
            creative.equals(
                Arrays.asList(
                    "place minecraft:stained_glass@5 SelfTestAlice",
                    "break minecraft:stained_glass@5 SelfTestAlice")),
            String.valueOf(creative));
        check("both players joined", counts.getOrDefault("player_join", 0) >= 2, null);
        check("gamemode changes ×2", counts.getOrDefault("gamemode_change", 0) >= 2, null);
        check("difficulty changes ×2", counts.getOrDefault("difficulty_change", 0) >= 2, null);
        // count only the SelfTest classes. On a pack server a formation of the TecTech fixture is logged too, and
        // checked below
        final long selfTestFormations = selectEventData(lines, "multiblock_formed").stream()
            .filter(
                data -> data.get("class")
                    .getAsString()
                    .startsWith("SelfTest"))
            .count();
        check(
            "multiblock dedupe, hatch-tier and coil-tier changes re-log, formation marker",
            selfTestFormations == 8,
            "count=" + selfTestFormations);
        final List<JsonObject> logFailures = selectEventData(lines, "multiblock_log_failed");
        check(
            "multiblock_log_failed: one line for the broken controller, logged twice",
            logFailures.size() == 1 && "SelfTestBrokenController".equals(
                logFailures.get(0)
                    .get("class")
                    .getAsString())
                && "formation".equals(
                    logFailures.get(0)
                        .get("what")
                        .getAsString())
                && logFailures.get(0)
                    .get("x")
                    .getAsInt() == offsetX(-8),
            String.valueOf(logFailures));
        if (Compat.GT5.isLoaded()) {
            final long tecTechFormations = selectEventData(lines, "multiblock_formed").stream()
                .filter(
                    data -> "MTEActiveTransformer".equals(
                        data.get("class")
                            .getAsString())
                        && data.get("x")
                            .getAsInt() == offsetX(8)
                        && data.get("z")
                            .getAsInt() == offsetZ(8))
                .count();
            check(
                "tectech multiblock formed on its own tick check, once",
                tecTechFormations == 1,
                "count=" + tecTechFormations);
            final JsonObject tecTech = selectEventData(lines, "multiblock_formed").stream()
                .filter(
                    data -> "MTEActiveTransformer".equals(
                        data.get("class")
                            .getAsString()))
                .findFirst()
                .orElse(null);
            checkTecTechFootprint(tecTech, lines);
            check(
                "tectech multi-amp hatch read: EV at 4 amps",
                tecTech != null && tecTech.has("energyTier")
                    && "EV".equals(
                        tecTech.get("energyTier")
                            .getAsString())
                    && tecTech.has("energyAmps")
                    && tecTech.get("energyAmps")
                        .getAsLong() == 4,
                String.valueOf(tecTech));
        }
        final List<JsonObject> bulk = selectEventData(lines, "bulk_placed");
        check(
            "manipulator: one bulk_placed, the placed spot listed, the existing machine counted",
            bulk.size() == 1 && bulk.get(0)
                .getAsJsonArray("machines")
                .toString()
                .equals("[[" + offsetX(20) + "," + offsetY(0) + "," + offsetZ(0) + ",0]]")
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
            counts.getOrDefault("command_output", 0) == 2 && selectEventData(lines, "command_output").stream()
                .allMatch(
                    data -> "gamerule".equals(
                        data.get("cmd")
                            .getAsString())
                        && data.getAsJsonArray("lines")
                            .size() > 0),
            "count=" + counts.getOrDefault("command_output", 0));
        check(
            "watched_block place ×2 + break",
            counts.getOrDefault("watched_block", 0) == 3,
            "count=" + counts.getOrDefault("watched_block", 0));
        final Set<String> outcomes = new HashSet<>();
        for (JsonObject data : selectEventData(lines, "invsee_close")) {
            outcomes.add(
                data.get("outcome")
                    .getAsString());
        }
        check(
            "invsee view outcomes",
            outcomes.containsAll(
                Arrays.asList(
                    "changes lost (target online at close)",
                    "saved, last writer wins",
                    "changes lost (view not closed)"))
                && counts.getOrDefault("invsee_close", 0) == 5,
            "outcomes=" + outcomes + " closes=" + counts.getOrDefault("invsee_close", 0));
        check(
            "trash_can_closed carries the chest's contents",
            selectEventData(lines, "trash_can_closed").stream()
                .anyMatch(
                    data -> data.getAsJsonArray("items")
                        .size() == 1),
            null);
        check(
            "node_census found the watched tile",
            selectEventData(lines, "node_census").stream()
                .anyMatch(
                    data -> data.get("nodes")
                        .getAsInt() >= 1),
            null);
        check(
            "world_saving off line carries lastSave",
            selectEventData(lines, "world_saving").stream()
                .anyMatch(data -> data.has("lastSave")),
            null);
        check(
            "creative_slot unchanged slot not logged",
            counts.getOrDefault("creative_slot", 0) == 1,
            "count=" + counts.getOrDefault("creative_slot", 0));
        final List<JsonObject> saving = selectEventData(lines, "world_saving");
        check(
            "world_saving off then on",
            saving.size() == 2 && !saving.get(0)
                .get("enabled")
                .getAsBoolean()
                && saving.get(1)
                    .get("enabled")
                    .getAsBoolean(),
            "count=" + saving.size());
        check(
            "death killer logged",
            selectEventData(lines, "death").stream()
                .anyMatch(
                    data -> data.has("killer") && "Zombie".equals(
                        data.get("killer")
                            .getAsString())),
            null);
        check(
            "multiblock energyTier logged",
            selectEventData(lines, "multiblock_formed").stream()
                .anyMatch(
                    data -> data.has("energyTier") && "UV".equals(
                        data.get("energyTier")
                            .getAsString())),
            null);
        check(
            "nbt_edit carries the before state",
            selectEventData(lines, "nbt_edit").stream()
                .anyMatch(
                    data -> data.has("beforeB64") && "cafebabe".equals(
                        data.get("beforeHash")
                            .getAsString())),
            null);
        if (nbtEditHashes == null) {
            check("nbt_edit mixin drill", true, "skipped: ServerUtilities not loaded");
        } else {
            final String drillTarget = "block " + offsetX(5) + "," + offsetY(0) + "," + offsetZ(0);
            check(
                "nbt_edit mixin drill: before and after hashes",
                selectEventData(lines, "nbt_edit").stream()
                    .anyMatch(
                        data -> drillTarget.equals(
                            data.get("target")
                                .getAsString())
                            && data.has("beforeHash")
                            && nbtEditHashes[0].equals(
                                data.get("beforeHash")
                                    .getAsString())
                            && nbtEditHashes[1].equals(
                                data.get("nbtHash")
                                    .getAsString())),
                "expected before=" + nbtEditHashes[0] + " after=" + nbtEditHashes[1]);
            final ItemStack edited = ((TileEntityChest) server.worldServers[0]
                .getTileEntity(offsetX(5), offsetY(0), offsetZ(0))).getStackInSlot(0);
            check(
                "nbt_edit mixin drill: edit applied",
                edited != null && edited.stackSize == 2,
                edited == null ? "slot empty" : "count=" + edited.stackSize);
        }
        checkMixinsApplied();
        // The checks below are for events of optional mods, so their event types are not in EXPECTED_TYPES. The jar
        // runs on servers without those mods, and there the events never appear.
        if (Compat.GT5.isLoaded()) {
            checkStructureTierPlans();
            checkStructureTiersOnThisSide(lines);
        }
        if (Compat.BETTER_QUESTING.isLoaded()) {
            check("event logged: quest_db", counts.getOrDefault("quest_db", 0) > 0, null);
        }
        if (StorageCensus.hasAnythingToCensus()) {
            check("event logged: storage_census", counts.getOrDefault("storage_census", 0) > 0, null);
        }
        if (Compat.GT5.isLoaded()) {
            final JsonObject power = selectEventData(lines, "power_census").stream()
                .findFirst()
                .orElse(null);
            check(
                "power census read the LSC and wireless EU",
                power != null && power.get("lscs")
                    .getAsInt() >= 1
                    && power.get("readErrors")
                        .getAsInt() == 0
                    && new BigInteger(
                        power.get("wirelessStored")
                            .getAsString()).compareTo(BigInteger.valueOf(SEEDED_WIRELESS_EU))
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
            final String eventType = line.get("t")
                .getAsString();
            if (eventType.equals("timing_started")) {
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
            check("chain verifies", "OK".equals(result.verdict()), result.verdict());
        } catch (Exception e) {
            check("chain verifies", false, String.valueOf(e));
        }
    }

    /** Collect the {@code data} object of each line with event type {@code eventType}, in log order. */
    private static List<JsonObject> selectEventData(List<JsonObject> lines, String eventType) {
        return lines.stream()
            .filter(
                line -> eventType.equals(
                    line.get("t")
                        .getAsString()))
            .map(line -> line.getAsJsonObject("data"))
            .collect(Collectors.toList());
    }

    /**
     * Check the footprint of the Active Transformer's formation line: a 3×3×3 box that contains the controller and
     * the hatch, and the facing of the live controller. No footprint line is logged for the forced re-check at step
     * 30. One footprint line, with the new rotation and the same box, is logged for the rotation at step 31.
     */
    private void checkTecTechFootprint(JsonObject tecTech, List<JsonObject> lines) {
        final int BOUNDS_LENGTH = 6;
        final int[] box = new int[BOUNDS_LENGTH];
        final boolean hasBox = tecTech != null && tecTech.has("bounds")
            && tecTech.getAsJsonArray("bounds")
                .size() == BOUNDS_LENGTH;
        if (hasBox) {
            for (int i = 0; i < BOUNDS_LENGTH; i++) {
                box[i] = tecTech.getAsJsonArray("bounds")
                    .get(i)
                    .getAsInt();
            }
        }
        final int[] hatch = TecTechFixture.hatchPosition;
        check(
            "tectech footprint: 3x3x3 box around the controller and the hatch",
            hasBox && box[3] - box[0] == 2
                && box[4] - box[1] == 2
                && box[5] - box[2] == 2
                && isInBox(box, new int[] { offsetX(8), offsetY(0), offsetZ(8) })
                && hatch != null
                && isInBox(box, hatch),
            tecTech == null ? "no formation line"
                : String.valueOf(tecTech.get("bounds")) + " hatch=" + Arrays.toString(hatch));
        check(
            "tectech footprint: facing, rotation and flip of the live controller",
            tecTech != null && tecTechFacing != null
                && tecTech.has("facing")
                && tecTechFacing[0].equals(
                    tecTech.get("facing")
                        .getAsString())
                && tecTech.has("rotation")
                && tecTechFacing[1].equals(
                    tecTech.get("rotation")
                        .getAsString())
                && tecTech.has("flip")
                && tecTechFacing[2].equals(
                    tecTech.get("flip")
                        .getAsString()),
            "line=" + tecTech + " live=" + Arrays.toString(tecTechFacing));
        final List<JsonObject> footprints = selectEventData(lines, "multiblock_footprint").stream()
            .filter(
                data -> "MTEActiveTransformer".equals(
                    data.get("class")
                        .getAsString()))
            .collect(Collectors.toList());
        check(
            "tectech footprint line only for the rotation, with the new rotation and the same box",
            footprints.size() == 1 && "CLOCKWISE".equals(
                footprints.get(0)
                    .get("rotation")
                    .getAsString())
                && tecTech != null
                && String.valueOf(tecTech.get("bounds"))
                    .equals(
                        String.valueOf(
                            footprints.get(0)
                                .get("bounds"))),
            footprints.toString());
    }

    /**
     * Test whether {@code point}, as {x, y, z}, lies in {@code box}, as {minX, minY, minZ, maxX, maxY, maxZ} with
     * both corners inclusive.
     */
    private static boolean isInBox(int[] box, int[] point) {
        return point[0] >= box[0] && point[1] >= box[1]
            && point[2] >= box[2]
            && point[0] <= box[3]
            && point[1] <= box[4]
            && point[2] <= box[5];
    }

    /**
     * Load every mixin target, vanilla or of an installed mod, and check the target for an injected audit$ member. A
     * mixin applies when the target class loads, and the scenario never loads some targets, such as the trash slot, the
     * /inv view container and the edit handlers of BetterQuesting. A bad target would then crash a real game later, not
     * this boot.
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
            if (target[0] != null && !Loader.isModLoaded(target[0])) {
                continue;
            }
            try {
                final Class<?> targetClass = Class.forName(target[1], true, SelfTest.class.getClassLoader());
                boolean injected = false;
                for (Method method : targetClass.getDeclaredMethods()) {
                    injected |= method.getName()
                        .contains("audit$");
                }
                for (Field field : targetClass.getDeclaredFields()) {
                    injected |= field.getName()
                        .contains("audit$");
                }
                check("mixin applied: " + target[1], injected, injected ? null : "no audit$ member");
            } catch (ClassNotFoundException | LinkageError e) {
                check("mixin applied: " + target[1], false, String.valueOf(e));
            }
        }
    }

    /**
     * Check the tier fields that {@link StructureTiers#planKeys} returns for real controller classes. A renamed GT
     * field then fails this check, and the tier does not go missing from a run's log with no warning. The classes
     * load without their static initialisers.
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
            { "gregtech.common.tileentities.machines.multi.MTENanoForge", "mSpecialTier" },
            // on a dedicated server, the fields of this class can't all be listed, so StructureTiers looks up
            // glassTier by name
            { "kubatech.tileentity.gregtech.multiblock.MTEExtremeEntityCrusher", "glassTier" } };
        // GT 5.09.51.482 of pack 2.8.4 has none of these classes. On pack 2.8.4, the FML version string of GT is
        // "MC1710", and a version compare can't order that string against a GT release
        final Set<String> newerThanPack284 = Set
            .of("gregtech.common.tileentities.machines.multi.foundry.MTEExoFoundry");
        final boolean pack284Gt = !GtMachineSites.hasLocalNameKeyGetter();
        for (String[] row : expected) {
            final String checkName = "structure tiers planned: " + row[0].substring(row[0].lastIndexOf('.') + 1);
            try {
                final List<String> keys = StructureTiers
                    .planKeys(Class.forName(row[0], false, SelfTest.class.getClassLoader()));
                boolean all = true;
                for (int i = 1; i < row.length; i++) {
                    all &= keys.contains(row[i]);
                }
                check(checkName, all, keys.toString());
            } catch (ClassNotFoundException e) {
                final boolean expectedAbsent = pack284Gt && newerThanPack284.contains(row[0]);
                check(checkName, expectedAbsent, expectedAbsent ? "absent in GT 5.09.51" : String.valueOf(e));
            } catch (LinkageError e) {
                check(checkName, false, String.valueOf(e));
            }
        }
    }

    /**
     * Check that the tiers of every registered multiblock read without a throw on this side, and that the Extreme
     * Entity Crusher of step 3 logged one formation line with its glass tier. On a pass, the check detail has the
     * classes whose fields can't all be listed on this side.
     */
    private void checkStructureTiersOnThisSide(List<JsonObject> lines) {
        final JsonObject crusher = selectEventData(lines, "multiblock_formed").stream()
            .filter(
                data -> "MTEExtremeEntityCrusher".equals(
                    data.get("class")
                        .getAsString()))
            .findFirst()
            .orElse(null);
        check(
            "extreme entity crusher line carries its glass tier, read by name on this side",
            crusher != null && !crusher.has("structureUnreadable")
                && crusher.has("structure")
                && crusher.getAsJsonObject("structure")
                    .has("glassTier"),
            crusher == null ? "no line" : String.valueOf(crusher));
        final List<String> failed = FormationSideCheck.readEveryMultiblock();
        check(
            "structure tiers read on every multiblock",
            failed.isEmpty(),
            failed.isEmpty() ? "fields not listable on this side: " + FormationSideCheck.findUnlistableMultiblocks()
                : failed.toString());
        final long crusherFormations = selectEventData(lines, "multiblock_formed").stream()
            .filter(
                data -> "MTEExtremeEntityCrusher".equals(
                    data.get("class")
                        .getAsString())
                    && data.get("x")
                        .getAsInt() == offsetX(-6)
                    && data.get("z")
                        .getAsInt() == offsetZ(-6))
            .count();
        check("extreme entity crusher formation logged once", crusherFormations == 1, "count=" + crusherFormations);
    }

    /** Check that the pattern output appears in an AE2 census file as a count-0 entry flagged craftable. */
    private void checkAe2Craftable(List<JsonObject> lines) {
        final List<String> seen = new ArrayList<>();
        for (JsonObject data : selectEventData(lines, "ae2_snapshot")) {
            final Path snapshotPath = session.snapshotDir()
                .resolve(
                    data.get("file")
                        .getAsString());
            try (InputStream in = new GZIPInputStream(Files.newInputStream(snapshotPath))) {
                final JsonObject snapshot = new JsonParser().parse(new InputStreamReader(in, StandardCharsets.UTF_8))
                    .getAsJsonObject();
                for (JsonElement element : snapshot.getAsJsonArray("items")) {
                    final JsonObject item = element.getAsJsonObject();
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
                seen.add(snapshotPath.getFileName() + " craftableItems=" + data.get("craftableItems"));
            } catch (Exception e) {
                seen.add(snapshotPath.getFileName() + " unreadable: " + e);
            }
        }
        check("ae2 census flags the pattern output craftable", false, AE2_CRAFTABLE_KEY + " absent from " + seen);
    }

    /**
     * Check the one worldedit_edit line of the fixture's edit session: the placed and removed block counts, and the
     * chest's tile entity.
     */
    private void checkWorldEdit(List<JsonObject> lines) {
        final List<JsonObject> edits = selectEventData(lines, "worldedit_edit");
        if (edits.size() != 1) {
            check("worldedit_edit: one line for the fixture's session", false, "lines=" + edits);
            return;
        }
        final JsonObject edit = edits.get(0);
        final JsonObject placed = edit.getAsJsonObject("placed");
        final JsonObject removed = edit.getAsJsonObject("removed");
        final boolean ok = edit.get("changed")
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
            && edit.get("tileEntitiesTotal")
                .getAsInt() == 1
            && edit.getAsJsonArray("tileEntities")
                .get(0)
                .getAsJsonArray()
                .get(3)
                .getAsString()
                .equals("minecraft:chest@0")
            && edit.get("dim")
                .getAsInt() == 0;
        check("worldedit_edit: exact placed/removed counts and the chest's tile", ok, String.valueOf(edit));
    }

    /**
     * Write {@code selftest.json} to the result directory, log the SELFTEST PASSED or SELFTEST FAILED line, and shut
     * the server down. A failed report write is logged, and the shutdown still happens.
     */
    private void finish() {
        done = true;
        final List<String> failures = new ArrayList<>();
        final JsonArray checkArray = new JsonArray();
        for (Check c : checks) {
            final JsonObject checkJson = new JsonObject();
            checkJson.addProperty("name", c.name());
            checkJson.addProperty("ok", c.ok());
            if (!c.detail()
                .isEmpty()) {
                checkJson.addProperty("detail", c.detail());
            }
            checkArray.add(checkJson);
            if (!c.ok()) {
                failures.add(c.name());
            }
        }
        final JsonObject report = new JsonObject();
        report.addProperty("pass", failures.isEmpty());
        report.add("checks", checkArray);
        try {
            if (!Files.isDirectory(resultDir)) {
                Files.createDirectories(resultDir);
            }
            Files.write(
                resultDir.resolve("selftest.json"),
                JsonUtil.GSON.toJson(report)
                    .getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            GtnhSpeedrunAudit.LOG.error("[selftest] could not write report", e);
        }
        if (failures.isEmpty()) {
            GtnhSpeedrunAudit.LOG.info("SELFTEST PASSED ({} checks)", checks.size());
        } else {
            GtnhSpeedrunAudit.LOG.error("SELFTEST FAILED: {}", String.join(", ", failures));
        }
        server.initiateShutdown();
    }

    /** A controller whose formation logging throws, for the multiblock_log_failed path. */
    private static final class SelfTestBrokenController implements GtFormationLogger {

        @Override
        public void audit$logFormation() {
            throw new IllegalStateException("selftest");
        }

        @Override
        public void audit$logFootprintChange() {
            throw new IllegalStateException("selftest");
        }
    }

    /** One check result. {@code detail} is empty when the check has no detail. */
    private record Check(String name, boolean ok, String detail) {}
}
