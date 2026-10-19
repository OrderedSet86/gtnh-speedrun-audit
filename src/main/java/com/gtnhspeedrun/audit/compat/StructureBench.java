package com.gtnhspeedrun.audit.compat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import net.minecraft.block.Block;
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
 * Time GT structure checks with and without the multiblock bounds mixin. The system property
 * {@code speedrunaudit.structurebench=<dir>} turns the bench on: {@code ./gradlew runServer -Pstructurebench=<dir>} in
 * dev, or the -D flag on a pack server. The baseline is a second boot with -Dspeedrunaudit.noStructureBounds=1, and on
 * that boot the mixin isn't applied.
 *
 * <p>
 * The StructureLib creative construct builds each structure with no hatches, so no structure forms. Each
 * {@code checkStructure(true)} walks every position and then fails. The bounds mixin collects the visited positions
 * on each such check. Each structure gets {@link #WARMUP_BATCHES} warm-up batches, then {@link #MEASURED_BATCHES}
 * measured batches, of {@link #CHECKS_PER_BATCH} checks each.
 * Each report row has the minimum and median nanoseconds per check, and the minimum allocated bytes per check from
 * {@code ThreadMXBean}. Then the server stops.
 */
public final class StructureBench {

    public static final String PROPERTY = "speedrunaudit.structurebench";

    private static final List<Structure> STRUCTURES = List.of(
        new Structure("Assembly Line x15", 1170, 15),
        new Structure("LSC x40 layers", 13106, 40),
        new Structure("Fusion MK1", 1193, 1),
        new Structure("EBF", 1000, 1));
    private static final int BUILD_TICK = 40;
    private static final int MEASURE_TICK = 200;
    private static final int WARMUP_BATCHES = 20;
    private static final int MEASURED_BATCHES = 5;
    private static final int CHECKS_PER_BATCH = 100;
    private static final int SPACING_ALONG_X = 48;
    private static final int HEIGHT_ABOVE_SPAWN = 60;

    private final MinecraftServer server;
    private final Path outDir;
    private final List<IGregTechTileEntity> built = new ArrayList<>();
    private int tick;

    public StructureBench(MinecraftServer server, Path outDir) {
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
        } catch (IOException | RuntimeException | LinkageError e) { // noqa: NAR142, degrade and report over GT
                                                                    // structure code
            GtnhSpeedrunAudit.LOG.error("[structurebench] failed", e);
            server.initiateShutdown();
        }
    }

    /**
     * Place and construct each structure in {@link #STRUCTURES} in a row along x above the spawn point.
     *
     * @throws IllegalStateException when a placed controller is not a constructable GT machine
     */
    private void build(WorldServer world) {
        final Block machines = (Block) Block.blockRegistry.getObject("gregtech:gt.blockmachines");
        final ChunkCoordinates spawn = world.getSpawnPoint();
        for (int i = 0; i < STRUCTURES.size(); i++) {
            final Structure structure = STRUCTURES.get(i);
            final int x = spawn.posX + i * SPACING_ALONG_X;
            final int y = spawn.posY + HEIGHT_ABOVE_SPAWN;
            final int z = spawn.posZ;
            final ItemStack controller = new ItemStack(machines, structure.stackSize(), structure.mteId());
            TecTechFixture.place(FakePlayerFactory.getMinecraft(world), controller, new WorldPos(world, x, y, z));
            final TileEntity te = world.getTileEntity(x, y, z);
            if (!(te instanceof IGregTechTileEntity base
                && base.getMetaTileEntity() instanceof IConstructable constructable)) {
                throw new IllegalStateException(
                    structure.name() + ": no constructable controller at " + x + "," + y + "," + z);
            }
            constructable.construct(controller, false);
            built.add(base);
        }
    }

    /** Time the checks of each built structure. The rows are a mode row, a header row, then one row per structure. */
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
                runCheckBatch(multi, base);
            }
            final long[] nanos = new long[MEASURED_BATCHES];
            long minBytes = Long.MAX_VALUE;
            for (int b = 0; b < MEASURED_BATCHES; b++) {
                final long bytesBefore = AllocationMeter.readThreadAllocatedBytes();
                final long start = System.nanoTime();
                runCheckBatch(multi, base);
                nanos[b] = (System.nanoTime() - start) / CHECKS_PER_BATCH;
                final long bytes = AllocationMeter.readThreadAllocatedBytes() - bytesBefore;
                if (bytesBefore >= 0 && bytes >= 0) {
                    minBytes = Math.min(minBytes, bytes / CHECKS_PER_BATCH);
                }
            }
            Arrays.sort(nanos);
            rows.add(
                STRUCTURES.get(i)
                    .name() + "\t"
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

    private static void runCheckBatch(MTEMultiBlockBase multi, IGregTechTileEntity base) {
        for (int n = 0; n < CHECKS_PER_BATCH; n++) {
            multi.checkStructure(true, base);
        }
    }

    private void writeReport(List<String> rows) throws IOException {
        Files.createDirectories(outDir);
        final String mode = System.getProperty(AuditMixinPlugin.NO_STRUCTURE_BOUNDS_PROPERTY) == null ? "bounds"
            : "baseline";
        Files.write(outDir.resolve("structurebench-" + mode + ".tsv"), rows, StandardCharsets.UTF_8);
        for (String row : rows) {
            GtnhSpeedrunAudit.LOG.info("[structurebench] {}", row);
        }
    }

    /**
     * One benchmarked GT multiblock. The creative construct reads {@code stackSize} as the slice count of an Assembly
     * Line and the layer count of an LSC.
     */
    private record Structure(String name, int mteId, int stackSize) {}
}
