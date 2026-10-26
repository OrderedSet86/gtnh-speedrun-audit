package com.gtnhspeedrun.audit.selftest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.block.Block;
import net.minecraft.entity.monster.EntityZombie;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.inventory.ContainerChest;
import net.minecraft.inventory.InventoryBasic;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.network.NetworkManager;
import net.minecraft.server.MinecraftServer;
import net.minecraft.stats.AchievementList;
import net.minecraft.util.ChunkCoordinates;
import net.minecraft.util.DamageSource;
import net.minecraft.util.EntityDamageSource;
import net.minecraft.util.StatCollector;
import net.minecraft.world.ChunkPosition;
import net.minecraft.world.EnumDifficulty;
import net.minecraft.world.Explosion;
import net.minecraft.world.MinecraftException;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.WorldSettings;
import net.minecraftforge.common.util.BlockSnapshot;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.AchievementEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.world.BlockEvent;
import net.minecraftforge.event.world.ExplosionEvent;

import org.jetbrains.annotations.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.gtnhspeedrun.audit.compat.Compat;
import com.gtnhspeedrun.audit.core.JsonUtil;
import com.gtnhspeedrun.audit.core.SessionManager;
import com.gtnhspeedrun.audit.harness.Ae2Fixture;
import com.gtnhspeedrun.audit.harness.FormationSideCheck;
import com.gtnhspeedrun.audit.harness.NbtEditDrill;
import com.gtnhspeedrun.audit.harness.QuestEditDrill;
import com.gtnhspeedrun.audit.harness.TecTechFixture;
import com.gtnhspeedrun.audit.harness.TeleportDrill;
import com.gtnhspeedrun.audit.harness.WorldEditFixture;
import com.gtnhspeedrun.audit.harness.WorldPos;
import com.gtnhspeedrun.audit.snapshot.Ae2CensusFailures;
import com.gtnhspeedrun.audit.trackers.ClientModLists;
import com.gtnhspeedrun.audit.trackers.FormationGuard;
import com.gtnhspeedrun.audit.trackers.GtFormationLogger;
import com.gtnhspeedrun.audit.trackers.InventoryChanges;
import com.gtnhspeedrun.audit.trackers.InvseeViews;
import com.gtnhspeedrun.audit.trackers.MachineEvents;
import com.gtnhspeedrun.audit.trackers.MachinePlacementTracker;
import com.gtnhspeedrun.audit.trackers.MachinePlacementTracker.GtMachine;
import com.gtnhspeedrun.audit.trackers.MachineRemovals;
import com.gtnhspeedrun.audit.trackers.MachineSite;
import com.gtnhspeedrun.audit.trackers.MultiblockPower;
import com.gtnhspeedrun.audit.trackers.MultiblockSinks;
import com.gtnhspeedrun.audit.trackers.NbtEdit;
import com.gtnhspeedrun.audit.trackers.NeiSinks;
import com.mojang.authlib.GameProfile;

import cpw.mods.fml.common.eventhandler.EventBus;
import cpw.mods.fml.common.gameevent.PlayerEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/** The numbered steps of the self-test, which drive each loggable action with fake players and fixture blocks. */
public final class Scenario {

    /** GT MetaTileEntity id of the Lapotronic Supercapacitor (LSC) controller. */
    private static final int LSC_CONTROLLER_META = 13106;
    /** GT MetaTileEntity id of the bronze hull. */
    public static final int BRONZE_HULL_META = 1;
    public static final String GT_VAJRA = "gregtech:gt.Tool_Vajra";
    /** The Vajra's full charge, far above the EU of one use. */
    private static final double VAJRA_CHARGE_EU = 1e8;
    public static final long SEEDED_WIRELESS_EU = 12_345L;
    public static final float ALICE_RAW_YAW = 560.0F;
    public static final double ALICE_YAW = -160.0;
    public static final float ALICE_PITCH = 30.0F;
    private static final double ALICE_SAMPLE_MOVE_BLOCKS = 2.0;

    private final MinecraftServer server;
    private final SessionManager session;
    /**
     * The fake players' events go to the audit listeners only. On the global buses, every mod of a real pack would get
     * the events. A packet from a login handler then makes the outbound dispatch of FML throw a NullPointerException,
     * because a fake player has no netty channel. The angel ring of Extra Utilities sends such a packet, first seen on
     * pack 2.9.0-RC-1.
     */
    private final EventBus bus = new EventBus();
    private final CheckList checks;

    private EntityPlayerMP alice;
    private EntityPlayerMP bob;
    private long onlineTicksAtLogout = -1;
    private long ticksAtLogout = -1;
    /** The Active Transformer's live {direction, rotation, flip}, read at step 30. */
    private String[] tecTechFacing;
    /** The before and after hashes of the live /nbtedit drill, or null without ServerUtilities. */
    private String[] nbtEditHashes;
    private long nbtEditFloodMs = -1;
    private long questEditFloodMs = -1;
    private boolean questEditFloodByOp;
    /** The feet positions {x, y, z} of the /home and /tpl drill teleports, or null without ServerUtilities. */
    private double[][] teleportFeet;
    /**
     * The spot that every fixture block is placed relative to, above the world spawn and in the spawn chunks. The
     * spawn chunks generate at startup and stay loaded with nobody online. Fixed coordinates would make the result
     * depend on the seed. The chunk-generation throttle of Hodgepodge returns an empty chunk for a chunk never
     * generated, and a setBlock() into that chunk is dropped with no error. The spawn chunks are also loaded after a
     * restart, so the structure check runs then, and the restart drill can check that no new line is logged for the
     * TecTech fixture.
     */
    private ChunkCoordinates origin;

    public Scenario(SessionManager session, CheckList checks, List<Object> auditListeners) {
        this.server = session.server();
        this.session = session;
        this.checks = checks;
        for (Object listener : auditListeners) {
            bus.register(listener);
        }
    }

    /**
     * Run step {@code stepNumber} of the scenario, and return true after the last step. A step number with no case
     * does nothing, so the scenario waits for one step.
     */
    @SuppressWarnings("unchecked")
    public boolean runStep(int stepNumber) {
        final WorldServer overworld = server.worldServers[0];
        switch (stepNumber) {
            case 0 -> { // multiplayer join: two fake players
                checks.check(
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
                alice.setPositionAndRotation(offsetX(0), offsetY(1), offsetZ(0), 0, ALICE_PITCH);
                // a real player's yaw grows past 360 as the player turns. The logged yaw is wrapped to ALICE_YAW
                alice.rotationYaw = ALICE_RAW_YAW;
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
                checks.check(
                    "clock running after movement",
                    session.logger()
                        .clock()
                        .get() > 0,
                    null);
                checks.check(
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
                    checks.check("world save for lastSave", false, String.valueOf(e));
                }
            }
            case 3 -> {
                alice.theItemInWorldManager.setGameType(WorldSettings.GameType.SURVIVAL);
                alice.inventory.setInventorySlotContents(0, new ItemStack(Items.diamond, 3));
                placeAe2GridHost(overworld);
                seedPowerCensus(overworld, alice);
                buildTecTechMulti(overworld, alice);
                logCrusherFormation(overworld, alice);
                removeGtHulls(overworld, alice);
            }
            case 4 -> session.runSnapshotHooks();
            case 5 -> {
                // pos_sample: a move of more than `PositionRow.STILL_TENTHS` tenths of a block gets a row. Step 6 moves
                // alice back
                alice.setPosition(alice.posX + ALICE_SAMPLE_MOVE_BLOCKS, alice.posY, alice.posZ);
                if (Compat.WORLDEDIT.isLoaded()) {
                    final String problem = WorldEditFixture.edit(fixturePos(overworld, offset(20, 17, 20)));
                    if (problem != null) {
                        checks.check("worldedit edit ran", false, problem);
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
                alice.setPosition(alice.posX - ALICE_SAMPLE_MOVE_BLOCKS, alice.posY, alice.posZ);
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
                // openInventory has no parent. The parent of mineWood is openInventory, and a posted event unlocks
                // nothing, so the parent of mineWood stays locked
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
                driveRemovals(overworld);
                // A machine placed with a tool in the main hand, such as a Vajra or a StructureLib projector on
                // the pack. The machine_placed line has the tool as itemKey and the machine from the world.
                alice.inventory
                    .setInventorySlotContents(alice.inventory.currentItem, new ItemStack(Items.diamond_pickaxe));
                place(offset(3, 0, 0), Blocks.iron_block);

                // the sink layer below the mixins. The mixins bind only on a pack server
                NeiSinks.logNeiPacket(toUuidString(alice), "SelfTestAlice", 1);
                NeiSinks.NeiCheat.of("give")
                    .player(alice)
                    .item("minecraft:diamond@0", 64)
                    .log();
                final MachineSite multi = new MachineSite("SelfTestMulti", "test.multi", 0, 1, 2, 3);
                MultiblockSinks.logMultiblockFormed("gt", multi);
                MultiblockSinks.logMultiblockFormed("gt", multi); // dedupe
                // a creative tab take is logged. A packet that leaves the slot unchanged is not
                final ItemStack creativeDiamonds = new ItemStack(Items.diamond, 64);
                InventoryChanges
                    .logCreativeSlot(alice, new InventoryChanges.SlotChange(36, creativeDiamonds, null), true);
                InventoryChanges.logCreativeSlot(
                    alice,
                    new InventoryChanges.SlotChange(36, creativeDiamonds, creativeDiamonds.copy()),
                    true);
                // hatch tiers are in the dedupe key: LV is logged, LV again is deduped, the UV upgrade is logged
                final MultiblockPower lv = MultiblockPower.ofEnergyHatches(2, "LV");
                final MultiblockPower uv = MultiblockPower.ofEnergyHatches(2, "UV");
                final MachineSite hatched = new MachineSite("SelfTestMulti", "test.multi", 0, 7, 8, 9);
                MultiblockSinks.logMultiblockFormed("gt", hatched, lv);
                MultiblockSinks.logMultiblockFormed("gt", hatched, lv);
                MultiblockSinks.logMultiblockFormed("gt", hatched, uv);
                final MachineSite machine = new MachineSite("SelfTestMachine", "test.machine", 0, 4, 5, 6);
                MachineEvents.logGtExplosion(machine, 42);
                // coil and structure tiers are in the dedupe key too: LV is logged, LV again is deduped, HV is logged
                final MultiblockPower coilLv = MultiblockPower
                    .ofEnergyHatches(1, "EV", Collections.singletonMap("mCoilLevel", "LV"));
                final MultiblockPower coilHv = MultiblockPower
                    .ofEnergyHatches(1, "EV", Collections.singletonMap("mCoilLevel", "HV"));
                final MachineSite ebf = new MachineSite("SelfTestEbf", "test.ebf", 0, 10, 11, 12);
                MultiblockSinks.logMultiblockFormed("gt", ebf, coilLv);
                MultiblockSinks.logMultiblockFormed("gt", ebf, coilLv);
                MultiblockSinks.logMultiblockFormed("gt", ebf, coilHv);
                driveFormationMarker();
                MachineEvents.logGtPowerfail(machine);
                MachineEvents.logBloodAltarTier(new MachineSite("SelfTestAltar", null, 0, 20, 64, 20), 1, 2);
                InventoryChanges.logTrashInsert(alice, null, new ItemStack(Items.diamond, 2));
                final JsonArray invseeChanges = new JsonArray();
                invseeChanges.add(new JsonObject());
                driveInvseeViews(overworld, invseeChanges);
                final JsonObject giveExtra = new JsonObject();
                giveExtra.addProperty("inserted", 0);
                NeiSinks.NeiCheat.of("give")
                    .player(alice)
                    .item("minecraft:diamond@0", 64)
                    .extra(giveExtra)
                    .log();
                final NBTTagCompound beforeEdit = new NBTTagCompound();
                beforeEdit.setInteger("charge", 5);
                NbtEdit.of("player", "player SelfTestAlice")
                    .player(alice)
                    .written("deadbeef", null)
                    .before("cafebabe", JsonUtil.nbtToB64(beforeEdit))
                    .log();
                // the mixin itself, through the packet handler of ServerUtilities. The drill runs only on a pack
                // server
                nbtEditHashes = NbtEditDrill.editChest(alice, fixturePos(overworld, offset(5, 0, 0)));
                // forged replies with no edit open: a list of 2,000,000 empty elements, then a burst. A session stop
                // reports the dropped count. Report it here, so the checks can read it
                try {
                    nbtEditFloodMs = NbtEditDrill.sendForgedFlood(alice);
                } catch (IOException e) {
                    checks.check("nbt_edit forged flood built", false, String.valueOf(e));
                }
                questEditFloodByOp = server.getConfigurationManager()
                    .func_152596_g(alice.getGameProfile());
                try {
                    questEditFloodMs = QuestEditDrill.sendForgedFlood(alice);
                } catch (IOException | ReflectiveOperationException e) {
                    checks.check("quest_edit forged flood sent", false, String.valueOf(e));
                }
                session.attempts()
                    .reportAllWindows();
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
                Ae2CensusFailures.announceAe2CensusFailure(
                    Collections.singletonList(
                        Ae2CensusFailures.logAe2CensusFailure(
                            new Ae2CensusFailures.Ae2CensusFailure(
                                0,
                                "grid",
                                "selftest",
                                new IllegalStateException("selftest")))));
                final Map<String, String> fakeMods = new HashMap<>();
                fakeMods.put("selftestmod", "1.0");
                final Object key = new Object();
                ClientModLists.storeClientModList(key, fakeMods);
                checks.check("client modlist round-trip", fakeMods.equals(ClientModLists.readClientMods(key)), null);
            }
            case 9 -> {
                // the teleport mixins, through a real TeleportTask and /tpl of ServerUtilities. The drill runs only on
                // a pack server. Alice's leave snapshot is then at the /tpl spot
                teleportFeet = TeleportDrill
                    .teleport(alice, fixturePos(overworld, offset(0, 1, 3)), fixturePos(overworld, offset(0, 1, 6)));
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
                checks.check(
                    "pticks frozen after logout",
                    session.logger()
                        .clock()
                        .getOnline() == onlineTicksAtLogout,
                    null);
                checks.check(
                    "igt still running after logout",
                    session.logger()
                        .clock()
                        .get() > ticksAtLogout,
                    null);
                return true;
            }
            default -> {}
        }
        return false;
    }

    public int offsetX(int dx) {
        return readOrigin().posX + dx;
    }

    public int offsetY(int dy) {
        return readOrigin().posY + dy;
    }

    public int offsetZ(int dz) {
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
     * one processing pattern for a nether star, the {@code AE2_CRAFTABLE_KEY} of {@link OptionalModChecks}, powered so
     * the pattern is provided. The census then writes the pattern output as a count-0 craftable entry. The AE2 types
     * are in Ae2Fixture, so Scenario loads on a server without AE2.
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
            checks.check("ae2 grid with a pattern built", false, problem);
        }
    }

    /**
     * Give the power census an LSC controller to read and a wireless EU balance. The method uses the registry name and
     * reflection, so Scenario loads on a server without GregTech. The controller is unformed, so its capacitor counts
     * are empty, but the census reads every real field.
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
            checks.check("gt machine block present", false, "gregtech:gt.blockmachines not registered");
            return;
        }
        new ItemStack(machineBlock, 1, LSC_CONTROLLER_META)
            .tryPlaceItemIntoWorld(player, world, offsetX(14), offsetY(6), offsetZ(10), 1, 0.5f, 1f, 0.5f);
        try {
            Class.forName("gregtech.common.misc.WirelessNetworkManager")
                .getMethod("addEUToGlobalEnergyMap", UUID.class, long.class)
                .invoke(null, player.getUniqueID(), SEEDED_WIRELESS_EU);
        } catch (ReflectiveOperationException | RuntimeException e) { // noqa: NAR142, reflection into GT wireless code
            checks.check("wireless EU seeded", false, String.valueOf(e));
        }
    }

    /** Build an Active Transformer that forms only through the structure check in TTMultiblockBase.onPostTick(). */
    private void buildTecTechMulti(WorldServer world, EntityPlayerMP player) {
        if (!Compat.GT5.isLoaded()) {
            return;
        }
        final String problem = TecTechFixture.build(player, fixturePos(world, offset(8, 0, 8)));
        if (problem != null) {
            checks.check("tectech multiblock built", false, problem);
        }
    }

    /**
     * Log an Extreme Entity Crusher's formation on this dedicated server, the way the structure-check hook logs a
     * formation. The crusher has a field of a client-only type.
     * {@link OptionalModChecks#checkStructureTiersOnThisSide} checks the formation line.
     */
    private void logCrusherFormation(WorldServer world, EntityPlayerMP player) {
        if (!Compat.GT5.isLoaded()) {
            return;
        }
        final String problem = FormationSideCheck.logCrusherFormation(player, fixturePos(world, offset(-6, 0, -6)));
        checks.check("extreme entity crusher formation logged without a throw", problem == null, problem);
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

    /**
     * Drive one removal of each cause below the breakBlock mixin, with iron blocks for GT machines. Spots 30 to 37 get
     * broken with a pickaxe, wrenched with shears (the stand-in wrench), exploded, removed after a manipulator check,
     * removed with no cause, removed with no tile, right-clicked with a golden pickaxe (the stand-in Vajra), and broken
     * with the golden pickaxe. The expected result is seven machine_removed lines and one bulk_removed line with spot
     * 33.
     */
    private void driveRemovals(WorldServer world) {
        for (int x = 30; x <= 37; x++) {
            world.setBlock(offsetX(x), offsetY(0), offsetZ(0), Blocks.iron_block);
        }
        final GtMachine standIn = MachinePlacementTracker.readGtMachine(Blocks.iron_block, 0, null);
        alice.inventory.setInventorySlotContents(alice.inventory.currentItem, new ItemStack(Items.diamond_pickaxe));
        breakAsAlice(world, 30);
        removeStandIn(world, 30, standIn);
        alice.inventory.setInventorySlotContents(alice.inventory.currentItem, new ItemStack(Items.shears));
        breakAsAlice(world, 31);
        removeStandIn(world, 31, standIn);
        final Explosion explosion = new Explosion(world, null, offsetX(32), offsetY(0), offsetZ(0), 1);
        explosion.affectedBlockPositions.add(new ChunkPosition(offsetX(32), offsetY(0), offsetZ(0)));
        bus.post(new ExplosionEvent.Detonate(world, explosion, new ArrayList<>()));
        removeStandIn(world, 32, standIn);
        alice.inventory.setInventorySlotContents(alice.inventory.currentItem, new ItemStack(Items.stick));
        bus.post(
            new BlockEvent.PlaceEvent(
                BlockSnapshot.getBlockSnapshot(world, offsetX(33), offsetY(0), offsetZ(0)),
                Blocks.stone,
                alice));
        removeStandIn(world, 33, standIn);
        removeStandIn(world, 34, standIn);
        removeStandIn(world, 35, null);
        alice.inventory.setInventorySlotContents(alice.inventory.currentItem, new ItemStack(Items.golden_pickaxe));
        bus.post(
            new PlayerInteractEvent(
                alice,
                PlayerInteractEvent.Action.RIGHT_CLICK_BLOCK,
                offsetX(36),
                offsetY(0),
                offsetZ(0),
                1,
                world));
        removeStandIn(world, 36, standIn);
        breakAsAlice(world, 37);
        removeStandIn(world, 37, standIn);
    }

    private void breakAsAlice(WorldServer world, int dx) {
        bus.post(new BlockEvent.BreakEvent(offsetX(dx), offsetY(0), offsetZ(0), world, Blocks.iron_block, 0, alice));
    }

    /** Report the removal the way the breakBlock mixin does, then remove the block. */
    private void removeStandIn(WorldServer world, int dx, @Nullable GtMachine machine) {
        MachineRemovals.logMachineRemoval(
            new MachineSite("SelfTestRemoved", "test.removed", 0, offsetX(dx), offsetY(0), offsetZ(0)),
            machine);
        world.setBlockToAir(offsetX(dx), offsetY(0), offsetZ(0));
    }

    /**
     * Place two real GT bronze hulls. Remove the hull at x -12 with setBlockToAir(), so the breakBlock mixin logs the
     * removal with the cause {@code other}. Remove the hull at x -14 with a sneaking right-click of a charged GT Vajra,
     * the way a player does, so the removal is logged with the cause {@code vajra}.
     */
    private void removeGtHulls(WorldServer world, EntityPlayerMP player) {
        if (!Compat.GT5.isLoaded()) {
            return;
        }
        final Object block = Block.blockRegistry.getObject("gregtech:gt.blockmachines");
        if (!(block instanceof Block machineBlock)) {
            checks.check("gt machine block present", false, "gregtech:gt.blockmachines not registered");
            return;
        }
        placeGtHull(world, machineBlock, -12);
        world.setBlockToAir(offsetX(-12), offsetY(3), offsetZ(0));
        placeGtHull(world, machineBlock, -14);
        final Object vajraItem = Item.itemRegistry.getObject(GT_VAJRA);
        if (!(vajraItem instanceof Item vajra)) {
            checks.check("gt vajra present", false, GT_VAJRA + " not registered");
            return;
        }
        final ItemStack charged = new ItemStack(vajra);
        // the EU of an IC2 electric item is the item's "charge" tag
        final NBTTagCompound charge = new NBTTagCompound();
        charge.setDouble("charge", VAJRA_CHARGE_EU);
        charged.setTagCompound(charge);
        final ItemStack before = player.inventory.getStackInSlot(player.inventory.currentItem);
        player.inventory.setInventorySlotContents(player.inventory.currentItem, charged);
        // the Vajra removes a block on a sneaking right-click. A right-click without sneaking opens the machine
        player.setSneaking(true);
        player.theItemInWorldManager
            .activateBlockOrUseItem(player, world, charged, offsetX(-14), offsetY(3), offsetZ(0), 1, 0.5f, 1f, 0.5f);
        player.setSneaking(false);
        player.inventory.setInventorySlotContents(player.inventory.currentItem, before);
    }

    /** Place a bronze hull as alice at x {@code dx} on stone, with the spot cleared first, whatever the terrain. */
    private void placeGtHull(WorldServer world, Block machineBlock, int dx) {
        world.setBlock(offsetX(dx), offsetY(2), offsetZ(0), Blocks.stone);
        world.setBlockToAir(offsetX(dx), offsetY(3), offsetZ(0));
        new ItemStack(machineBlock, 1, BRONZE_HULL_META)
            .tryPlaceItemIntoWorld(alice, world, offsetX(dx), offsetY(2), offsetZ(0), 1, 0.5f, 1f, 0.5f);
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
        final MultiblockSinks.GtFormation ev = new MultiblockSinks.GtFormation(
            site,
            MultiblockPower.ofEnergyHatches(1, "EV"),
            null);
        final MultiblockSinks.GtFormation iv = new MultiblockSinks.GtFormation(
            site,
            MultiblockPower.ofEnergyHatches(1, "IV"),
            null);
        final String marker = MultiblockSinks.logGtMultiblockFormed(null, ev);
        final String reloaded = MultiblockSinks.logGtMultiblockFormed(marker, ev);
        checks
            .check("formation marker unchanged when nothing logged", marker != null && marker.equals(reloaded), marker);
        MultiblockSinks.logGtMultiblockFormed(marker, iv);
        MultiblockSinks.logGtMultiblockFormed(null, ev);
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
        InventoryChanges.InvseeClick.of(joined, alice)
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

    /** Force a re-check of the formed Active Transformer and read the facing. The footprint is unchanged. */
    private void recheckTecTechMulti(WorldServer world) {
        if (!Compat.GT5.isLoaded()) {
            return;
        }
        final String problem = TecTechFixture.recheck(fixturePos(world, offset(8, 0, 8)));
        checks.check("tectech multiblock re-checked", problem == null, problem);
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
        checks.check("tectech multiblock rotated and re-formed", problem == null, problem);
    }

    public String[] getTecTechFacing() {
        return tecTechFacing;
    }

    public String[] getNbtEditHashes() {
        return nbtEditHashes;
    }

    /** Return the time the nbtedit hook took for the forged reply with the large list, in ms, or -1 if not run. */
    public long getNbtEditFloodMs() {
        return nbtEditFloodMs;
    }

    /** Return the time the quest edit handler took for the forged edit with the large list, in ms, or -1. */
    public long getQuestEditFloodMs() {
        return questEditFloodMs;
    }

    /** Return whether the sender of the forged quest edits is an op, whose edits are never limited. */
    public boolean isQuestEditFloodByOp() {
        return questEditFloodByOp;
    }

    public double[][] getTeleportFeet() {
        return teleportFeet;
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
}
