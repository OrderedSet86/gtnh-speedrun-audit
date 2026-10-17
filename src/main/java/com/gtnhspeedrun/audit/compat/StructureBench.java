package com.gtnhspeedrun.audit.compat;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import net.minecraft.block.Block;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ChunkCoordinates;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.util.FakePlayerFactory;

import com.gtnewhorizon.structurelib.alignment.constructable.IConstructable;
import com.gtnhspeedrun.audit.GtnhSpeedrunAudit;
import com.gtnhspeedrun.audit.mixins.AuditMixinPlugin;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.metatileentity.implementations.MTEMultiBlockBase;

/**
 * Times GT structure checks with and without the multiblock bounds mixin. Activated by the system property
 * speedrunaudit.structurebench=&lt;dir&gt; ({@code ./gradlew runServer -Pstructurebench=<dir>} in dev, or the -D flag
 * on
 * a pack server). The baseline is a second boot with -Dspeedrunaudit.noStructureBounds=1, where the mixin is not
 * applied at all.
 *
 * <p>
 * The structures are built by StructureLib's creative construct with no hatches, so none forms: each
 * checkStructure(true) walks every position and then fails, which is the forming-check case the mixin collects on.
 * Each one runs WARMUP_BATCHES and then MEASURED_BATCHES of BATCH checks. The report gives the minimum and median
 * nanoseconds per check, and the minimum allocated bytes per check (ThreadMXBean). Then the server stops.
 */
public final class StructureBench {

    public static final String PROPERTY = "speedrunaudit.structurebench";

    /** {meta id, construct stack size}: the stack size sets an Assembly Line's slices and an LSC's layers. */
    private static final int[][] STRUCTURES = { { 1170, 15 }, // Assembly Line, 15 slices
        { 13106, 40 }, // Lapotronic Supercapacitor, 40 layers
        { 1193, 1 }, // Fusion Reactor MK1
        { 1000, 1 } }; // Electric Blast Furnace
    private static final String[] NAMES = { "Assembly Line x15", "LSC x40 layers", "Fusion MK1", "EBF" };
    private static final int BUILD_TICK = 40;
    private static final int MEASURE_TICK = 200;
    private static final int WARMUP_BATCHES = 20;
    private static final int MEASURED_BATCHES = 5;
    private static final int BATCH = 100;
    /** Blocks between structures along x, and the height above spawn. */
    private static final int SPACING = 48;
    private static final int HEIGHT = 60;

    private final MinecraftServer server;
    private final File outDir;
    private final List<IGregTechTileEntity> built = new ArrayList<>();
    private int tick;

    public StructureBench(MinecraftServer server, File outDir) {
        this.server = server;
        this.outDir = outDir;
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
            } else if (tick == MEASURE_TICK) {
                writeReport(measure());
                server.initiateShutdown();
            }
        } catch (Throwable t) {
            GtnhSpeedrunAudit.LOG.error("[structurebench] failed", t);
            server.initiateShutdown();
        }
    }

    private void build(WorldServer world) {
        final Block machines = (Block) Block.blockRegistry.getObject("gregtech:gt.blockmachines");
        final ChunkCoordinates spawn = world.getSpawnPoint();
        for (int i = 0; i < STRUCTURES.length; i++) {
            final int x = spawn.posX + i * SPACING;
            final int y = spawn.posY + HEIGHT;
            final int z = spawn.posZ;
            final ItemStack controller = new ItemStack(machines, STRUCTURES[i][1], STRUCTURES[i][0]);
            world.setBlockToAir(x, y, z);
            ((ItemBlock) controller.getItem()).placeBlockAt(
                controller,
                FakePlayerFactory.getMinecraft(world),
                world,
                x,
                y,
                z,
                1,
                0.5f,
                0.5f,
                0.5f,
                controller.getItemDamage());
            final TileEntity te = world.getTileEntity(x, y, z);
            if (!(te instanceof IGregTechTileEntity base) || !(base.getMetaTileEntity() instanceof IConstructable c)) {
                throw new IllegalStateException(NAMES[i] + ": no constructable controller at " + x + "," + y + "," + z);
            }
            c.construct(controller, false);
            built.add(base);
        }
    }

    private List<String> measure() {
        final List<String> rows = new ArrayList<>();
        rows.add(
            "mode=" + (System.getProperty(AuditMixinPlugin.NO_STRUCTURE_BOUNDS_PROPERTY) == null ? "bounds mixin"
                : "no mixin (baseline)"));
        rows.add("structure\tmin ns/check\tmedian ns/check\tmin bytes/check\tformed");
        for (int i = 0; i < built.size(); i++) {
            final IGregTechTileEntity base = built.get(i);
            final MTEMultiBlockBase multi = (MTEMultiBlockBase) base.getMetaTileEntity();
            for (int b = 0; b < WARMUP_BATCHES; b++) {
                batch(multi, base);
            }
            final long[] nanos = new long[MEASURED_BATCHES];
            long minBytes = Long.MAX_VALUE;
            for (int b = 0; b < MEASURED_BATCHES; b++) {
                final long bytesBefore = Ae2Bench.threadAllocatedBytes();
                final long start = System.nanoTime();
                batch(multi, base);
                nanos[b] = (System.nanoTime() - start) / BATCH;
                final long bytes = Ae2Bench.threadAllocatedBytes() - bytesBefore;
                if (bytesBefore >= 0 && bytes >= 0) {
                    minBytes = Math.min(minBytes, bytes / BATCH);
                }
            }
            Arrays.sort(nanos);
            rows.add(
                NAMES[i] + "\t"
                    + nanos[0]
                    + "\t"
                    + nanos[MEASURED_BATCHES / 2]
                    + "\t"
                    + (minBytes == Long.MAX_VALUE ? -1 : minBytes)
                    + "\t"
                    + multi.mMachine);
        }
        return rows;
    }

    private static void batch(MTEMultiBlockBase multi, IGregTechTileEntity base) {
        for (int n = 0; n < BATCH; n++) {
            multi.checkStructure(true, base);
        }
    }

    private void writeReport(List<String> rows) throws IOException {
        outDir.mkdirs();
        final String mode = System.getProperty(AuditMixinPlugin.NO_STRUCTURE_BOUNDS_PROPERTY) == null ? "bounds"
            : "baseline";
        final File out = new File(outDir, "structurebench-" + mode + ".tsv");
        Files.write(out.toPath(), rows, StandardCharsets.UTF_8);
        for (String row : rows) {
            GtnhSpeedrunAudit.LOG.info("[structurebench] {}", row);
        }
    }
}
