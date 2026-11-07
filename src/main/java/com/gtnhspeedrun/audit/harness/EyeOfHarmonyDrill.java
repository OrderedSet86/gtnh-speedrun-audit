package com.gtnhspeedrun.audit.harness;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ChunkCoordinates;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.util.BlockSnapshot;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.common.util.ForgeDirection;
import net.minecraftforge.event.world.BlockEvent;

import com.gtnewhorizon.structurelib.alignment.IAlignment;
import com.gtnewhorizon.structurelib.alignment.constructable.IConstructable;
import com.gtnewhorizon.structurelib.alignment.enumerable.ExtendedFacing;
import com.gtnewhorizon.structurelib.alignment.enumerable.Flip;
import com.gtnewhorizon.structurelib.alignment.enumerable.Rotation;
import com.gtnhspeedrun.audit.GtnhSpeedrunAudit;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.metatileentity.implementations.MTEMultiBlockBase;

/**
 * Build Eye of Harmony multiblocks above the spawn point, form them, then stop the server. The formation lines then
 * have the {@code extents} of a hollow structure that fills a small part of the structure's {@code bounds} box. Set the
 * system property {@code speedrunaudit.eohdrill=<count>} to turn the drill on.
 *
 * <p>
 * Each Eye of Harmony gets a different casing tier and a different front: north, east, south, west, then again. The
 * creative construct fills each of the 36 hatch spots with the boundary casing, and the Eye of Harmony forms only with
 * one input bus, two input hatches, one output bus and one output hatch. The hatch spots are the places of the one
 * block of the construct that occurs 36 times. LV hatches go on five of the hatch spots.
 */
public final class EyeOfHarmonyDrill {

    public static final String PROPERTY = "speedrunaudit.eohdrill";

    private static final int EYE_OF_HARMONY_MTE_ID = 15410;
    // input bus, two input hatches, output bus, output hatch: the hatches the Eye of Harmony requires
    private static final int[] HATCH_MTE_IDS = { 71, 51, 51, 81, 61 };
    private static final int HATCH_SPOTS = 36;
    private static final int SCAN_RADIUS = 34;
    private static final int SPACING = 80;
    private static final int HEIGHT_ABOVE_SPAWN = 70;
    private static final int BUILD_TICK = 40;
    private static final int RECHECK_TICK = 300;
    private static final int STOP_TICK = 400;
    private static final ForgeDirection[] FRONTS = { ForgeDirection.NORTH, ForgeDirection.EAST, ForgeDirection.SOUTH,
        ForgeDirection.WEST };

    private final MinecraftServer server;
    private final int count;
    private final List<IGregTechTileEntity> built = new ArrayList<>();
    private int tick;

    public EyeOfHarmonyDrill(MinecraftServer server, int count) {
        this.server = server;
        this.count = count;
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        tick++;
        try {
            if (tick == BUILD_TICK) {
                build(server.worldServers[0]);
            } else if (tick == RECHECK_TICK) {
                recheckUnformed();
            } else if (tick == STOP_TICK) {
                reportFormed();
                server.initiateShutdown();
            }
        } catch (RuntimeException | LinkageError e) { // noqa: NAR142, degrade and report over GT structure code
            GtnhSpeedrunAudit.LOG.error("[eohdrill] failed", e);
            server.initiateShutdown();
        }
    }

    /**
     * Place, turn and construct each Eye of Harmony on a grid centred on the spawn point, then put the hatches on.
     *
     * @throws IllegalStateException when a placed controller is not a constructable GT machine, or the construct has no
     *                               block that occurs {@link #HATCH_SPOTS} times
     */
    private void build(WorldServer world) {
        final Block machines = (Block) Block.blockRegistry.getObject("gregtech:gt.blockmachines");
        final ChunkCoordinates spawn = world.getSpawnPoint();
        final int columns = (int) Math.ceil(Math.sqrt(count));
        for (int i = 0; i < count; i++) {
            final int x = spawn.posX + (i % columns) * SPACING - (columns - 1) * SPACING / 2;
            final int y = spawn.posY + HEIGHT_ABOVE_SPAWN;
            final int z = spawn.posZ + (i / columns) * SPACING - (columns - 1) * SPACING / 2;
            final ItemStack controller = new ItemStack(machines, 1 + i, EYE_OF_HARMONY_MTE_ID);
            final WorldPos controllerPos = new WorldPos(world, x, y, z);
            placeAsPlayer(world, controller, controllerPos);
            final TileEntity te = world.getTileEntity(x, y, z);
            if (!(te instanceof IGregTechTileEntity base
                && base.getMetaTileEntity() instanceof IConstructable constructable
                && constructable instanceof IAlignment alignment)) {
                throw new IllegalStateException("no constructable Eye of Harmony at " + x + "," + y + "," + z);
            }
            alignment.setExtendedFacing(ExtendedFacing.of(FRONTS[i % FRONTS.length], Rotation.NORMAL, Flip.NONE));
            constructable.construct(controller, false);
            placeHatches(world, controllerPos);
            built.add(base);
        }
    }

    /** Put one hatch of {@link #HATCH_MTE_IDS} on each of the first hatch spots around {@code controllerPos}. */
    private static void placeHatches(WorldServer world, WorldPos controllerPos) {
        final List<WorldPos> spots = findHatchSpots(world, controllerPos);
        final Block machines = (Block) Block.blockRegistry.getObject("gregtech:gt.blockmachines");
        for (int h = 0; h < HATCH_MTE_IDS.length; h++) {
            placeAsPlayer(world, new ItemStack(machines, 1, HATCH_MTE_IDS[h]), spots.get(h));
        }
    }

    /**
     * Place the block of {@code stack} at {@code pos} as the fake player, then post Forge's place event, so the audit
     * logs a {@code machine_placed} line. {@code ItemBlock.placeBlockAt()} posts no place event.
     */
    private static void placeAsPlayer(WorldServer world, ItemStack stack, WorldPos pos) {
        final FakePlayer player = FakePlayerFactory.getMinecraft(world);
        final BlockSnapshot replaced = BlockSnapshot.getBlockSnapshot(world, pos.x(), pos.y(), pos.z());
        TecTechFixture.place(player, stack, pos);
        MinecraftForge.EVENT_BUS.post(new BlockEvent.PlaceEvent(replaced, Blocks.stone, player));
    }

    /**
     * Return the places of the block and meta that occur {@link #HATCH_SPOTS} times within {@link #SCAN_RADIUS} of
     * {@code controllerPos}, in scan order.
     *
     * @throws IllegalStateException when no block and meta occur that many times
     */
    private static List<WorldPos> findHatchSpots(WorldServer world, WorldPos controllerPos) {
        final Map<String, List<WorldPos>> placesByBlock = new HashMap<>();
        for (int dx = -SCAN_RADIUS; dx <= SCAN_RADIUS; dx++) {
            for (int dy = -SCAN_RADIUS; dy <= SCAN_RADIUS; dy++) {
                for (int dz = -SCAN_RADIUS; dz <= SCAN_RADIUS; dz++) {
                    final WorldPos pos = controllerPos.offset(dx, dy, dz);
                    final Block block = world.getBlock(pos.x(), pos.y(), pos.z());
                    if (block.isAir(world, pos.x(), pos.y(), pos.z())) {
                        continue;
                    }
                    placesByBlock
                        .computeIfAbsent(
                            Block.blockRegistry.getNameForObject(block) + "@"
                                + world.getBlockMetadata(pos.x(), pos.y(), pos.z()),
                            key -> new ArrayList<>())
                        .add(pos);
                }
            }
        }
        for (List<WorldPos> places : placesByBlock.values()) {
            if (places.size() == HATCH_SPOTS) {
                return places;
            }
        }
        throw new IllegalStateException("no block occurs " + HATCH_SPOTS + " times around " + controllerPos);
    }

    /** Run the structure check of each Eye of Harmony that the TecTech start-up check left unformed. */
    private void recheckUnformed() {
        for (IGregTechTileEntity base : built) {
            final MTEMultiBlockBase multi = (MTEMultiBlockBase) base.getMetaTileEntity();
            if (!multi.mMachine) {
                multi.checkStructure(true, base);
            }
        }
    }

    private void reportFormed() {
        for (IGregTechTileEntity base : built) {
            GtnhSpeedrunAudit.LOG.info(
                "eohdrill.formed x={} y={} z={} formed={}",
                base.getXCoord(),
                base.getYCoord(),
                base.getZCoord(),
                ((MTEMultiBlockBase) base.getMetaTileEntity()).mMachine);
        }
    }
}
