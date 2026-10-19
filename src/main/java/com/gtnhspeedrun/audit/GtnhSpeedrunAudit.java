package com.gtnhspeedrun.audit;

import java.io.IOException;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import net.minecraft.server.MinecraftServer;
import net.minecraftforge.common.MinecraftForge;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.google.gson.JsonObject;
import com.gtnhspeedrun.audit.command.CommandAudit;
import com.gtnhspeedrun.audit.compat.BackupAccess;
import com.gtnhspeedrun.audit.compat.BqQuestDb;
import com.gtnhspeedrun.audit.compat.BqQuestTracker;
import com.gtnhspeedrun.audit.compat.Compat;
import com.gtnhspeedrun.audit.compat.GtPowerCensus;
import com.gtnhspeedrun.audit.compat.GtWirelessAudit;
import com.gtnhspeedrun.audit.compat.WorldEditAudit;
import com.gtnhspeedrun.audit.core.SessionManager;
import com.gtnhspeedrun.audit.harness.Ae2Bench;
import com.gtnhspeedrun.audit.harness.StructureBench;
import com.gtnhspeedrun.audit.selftest.SelfTest;
import com.gtnhspeedrun.audit.snapshot.Ae2Snapshotter;
import com.gtnhspeedrun.audit.snapshot.InventorySnapshotter;
import com.gtnhspeedrun.audit.snapshot.KeyItemIndex;
import com.gtnhspeedrun.audit.snapshot.NodeCensus;
import com.gtnhspeedrun.audit.snapshot.SnapshotScheduler;
import com.gtnhspeedrun.audit.snapshot.StorageCensus;
import com.gtnhspeedrun.audit.trackers.BackupProbe;
import com.gtnhspeedrun.audit.trackers.CommandTracker;
import com.gtnhspeedrun.audit.trackers.DifficultyTracker;
import com.gtnhspeedrun.audit.trackers.FingerprintTracker;
import com.gtnhspeedrun.audit.trackers.GamemodeTracker;
import com.gtnhspeedrun.audit.trackers.GameruleTracker;
import com.gtnhspeedrun.audit.trackers.InvseeViews;
import com.gtnhspeedrun.audit.trackers.ItemIdTable;
import com.gtnhspeedrun.audit.trackers.MachinePlacementTracker;
import com.gtnhspeedrun.audit.trackers.MilestoneTracker;
import com.gtnhspeedrun.audit.trackers.PlayerSessionTracker;
import com.gtnhspeedrun.audit.trackers.StructureBounds;
import com.gtnhspeedrun.audit.trackers.TrashCanCommandTracker;
import com.gtnhspeedrun.audit.trackers.WorldSaving;
import com.gtnhspeedrun.audit.trackers.WorldSavingTracker;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStartedEvent;
import cpw.mods.fml.common.event.FMLServerStartingEvent;
import cpw.mods.fml.common.event.FMLServerStoppedEvent;
import cpw.mods.fml.common.event.FMLServerStoppingEvent;

/**
 * Start an audit session with each server, register the trackers, and stop the session with the server. The mod runs
 * on the server only, the integrated server of singleplayer included. Clients without the mod can join an audited
 * server ({@code acceptableRemoteVersions = "*"}).
 */
@Mod(
    modid = GtnhSpeedrunAudit.MODID,
    name = "GTNH Speedrun Audit",
    version = Tags.VERSION,
    acceptedMinecraftVersions = "[1.7.10]",
    acceptableRemoteVersions = "*")
public class GtnhSpeedrunAudit {

    public static final String MODID = "gtnhspeedrunaudit";
    public static final Logger LOG = LogManager.getLogger(MODID);
    // BackupAccess loads on a pack without ServerUtilities and reports the backup state as unknown there
    private static final BackupProbe BACKUPS = new BackupProbe(
        BackupAccess::isBackupRunning,
        BackupAccess::describeNewest);

    /** The live session, or null between worlds. The field is static, so mixin sinks can read it through session(). */
    private static volatile SessionManager session;

    private final List<Object> forgeBusListeners = new ArrayList<>();
    private final List<Object> fmlBusListeners = new ArrayList<>();
    /**
     * The call that unregisters the audit from the WorldEdit event bus, or null without WorldEdit. The field is a
     * Runnable, so no WorldEdit type appears in this class.
     */
    private Runnable worldEditStop;

    public static SessionManager session() {
        return session;
    }

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        AuditConfig.synchronizeConfiguration(event.getSuggestedConfigurationFile());
    }

    /**
     * Start the session and register every tracker for this server. After an IOException or RuntimeException, the
     * error is logged, the trackers registered so far are unregistered, the writer of a started session is closed, and
     * the server runs on with no session. A started session then has no session_end line, so the next start reads that
     * session as a crash.
     */
    @Mod.EventHandler
    public void serverStarting(FMLServerStartingEvent event) {
        SessionManager started = null;
        try {
            final String selftestDir = System.getProperty(SelfTest.PROPERTY);
            final boolean selftest = selftestDir != null;
            // the self-test scenarios use vanilla items and blocks. Point these settings at those items and blocks
            if (selftest) {
                AuditConfig.keyItems = new String[] { "minecraft:diamond" };
                AuditConfig.trackedPlacements = new String[] { "minecraft:diamond_block" };
                AuditConfig.watchedBlocks = new String[] { "minecraft:diamond_block" };
                AuditConfig.nodeCensusTiles = new String[] { "net.minecraft.tileentity.TileEntityChest" };
            }
            final SessionManager sm = new SessionManager(LOG, event.getServer());
            sm.start();
            started = sm;
            session = sm;

            final WorldSavingTracker worldSaving = new WorldSavingTracker(sm, BACKUPS);
            onForgeBus(worldSaving); // WorldEvent.Save
            sm.addTickHook(worldSaving::checkWorldSaving); // end of tick, before timing_started and the clocks

            final KeyItemIndex keyItems = new KeyItemIndex(AuditConfig.keyItems, sm.logger(), sm.anchor());
            final InventorySnapshotter inventory = new InventorySnapshotter(sm.logger(), keyItems);
            final SnapshotScheduler scheduler = new SnapshotScheduler(
                event.getServer(),
                inventory,
                AuditConfig.invSnapshotMinutes);
            sm.addSnapshotHook(() -> scheduler.snapshotAllPlayers("manual"));

            final MilestoneTracker milestones = new MilestoneTracker(sm.logger(), sm.anchor(), keyItems);
            onForgeBus(new CommandTracker(sm.logger()));
            onForgeBus(inventory); // LivingDeathEvent
            onForgeBus(milestones); // AchievementEvent
            final MachinePlacementTracker placements = new MachinePlacementTracker(
                sm.logger(),
                selftest ? SelfTest.STAND_IN_MACHINE_BLOCK : MachinePlacementTracker.GT_MACHINE_BLOCK,
                selftest ? SelfTest.STAND_IN_MANIPULATOR_PREFIX : MachinePlacementTracker.MANIPULATOR_PREFIX);
            onForgeBus(placements); // PlaceEvent, BreakEvent
            onFmlBus(placements); // end of tick: Matter Manipulator placements
            onFmlBus(inventory); // login/logout snapshots
            onFmlBus(milestones); // dimension changes, crafts
            onFmlBus(new PlayerSessionTracker(sm.logger(), AuditConfig.logPlayerIp, AuditConfig.flagClientMods));
            onFmlBus(new GamemodeTracker(sm.logger(), inventory, event.getServer()));
            onFmlBus(new DifficultyTracker(sm.logger(), event.getServer()));
            onFmlBus(new GameruleTracker(sm.logger(), event.getServer()));
            onFmlBus(scheduler);

            // NEVER load a class that uses the types of an optional mod before a check that the mod is loaded. Such
            // classes are in compat/ and harness/, and Ae2Snapshotter is in snapshot/
            if (Compat.BETTER_QUESTING.isLoaded()) {
                onForgeBus(new BqQuestTracker(sm.logger()));
            }
            if (Loader.isModLoaded("Thaumcraft") || Loader.isModLoaded("ThaumicHorizons") || selftest) {
                final NodeCensus nodes = new NodeCensus(sm.logger(), event.getServer());
                onFmlBus(nodes);
                sm.addSnapshotHook(() -> nodes.takeCensus("manual"));
            }
            if (StorageCensus.hasAnythingToCensus()) {
                final StorageCensus storage = new StorageCensus(sm.logger(), AuditConfig.storageCensusMinutes);
                onFmlBus(storage);
                sm.addSnapshotHook(() -> storage.takeCensus("manual"));
            }
            // InvseeViews and TrashCanCommandTracker reference vanilla types only, so they need no check for a mod.
            // Without ServerUtilities no view and no trash chest opens.
            onFmlBus(new InvseeViews()); // target logins, viewer logouts
            onFmlBus(new TrashCanCommandTracker(sm.logger()));
            if (Compat.GT5.isLoaded()) {
                // multiblock footprints
                StructureBounds.onServerStart();
                onFmlBus(new StructureBounds.Ticker());
                final String structureBenchDir = System.getProperty(StructureBench.PROPERTY);
                if (structureBenchDir != null && !structureBenchDir.isEmpty()) {
                    LOG.warn(
                        "STRUCTURE BENCH MODE ACTIVE — this server run times structure checks and then shuts down");
                    onFmlBus(new StructureBench(event.getServer(), Paths.get(structureBenchDir)));
                }
                final GtWirelessAudit wireless = new GtWirelessAudit(sm.logger());
                onForgeBus(wireless); // CommandEvent
                onFmlBus(wireless); // end-of-tick balance read
                final GtPowerCensus power = new GtPowerCensus(sm.logger(), event.getServer());
                onFmlBus(power);
                sm.addSnapshotHook(() -> power.takeCensus("manual"));
            }
            if (Compat.AE2.isLoaded()) {
                final Ae2Snapshotter ae2 = new Ae2Snapshotter(sm.logger(), keyItems, event.getServer());
                onFmlBus(ae2);
                sm.addSnapshotHook(ae2::requestCensus);

                final String benchDir = System.getProperty(Ae2Bench.PROPERTY);
                if (benchDir != null && !benchDir.isEmpty()) {
                    LOG.warn("AE2 BENCH MODE ACTIVE — this server run measures the census and then shuts down");
                    onFmlBus(new Ae2Bench(event.getServer(), Paths.get(benchDir)));
                }
            }

            if (Compat.WORLDEDIT.isLoaded()) {
                try {
                    final WorldEditAudit worldEdit = new WorldEditAudit(sm.logger());
                    worldEdit.register();
                    onFmlBus(worldEdit); // end of tick: one worldedit_edit per edit session
                    worldEditStop = worldEdit::unregister;
                } catch (LinkageError | RuntimeException e) { // noqa: NAR142, degrade and report over the WorldEdit API
                    // a WorldEdit build with another API leaves only WorldEdit edits unaudited
                    LOG.error("WorldEdit edits NOT audited — WorldEdit API mismatch", e);
                }
            }

            event.registerServerCommand(new CommandAudit());

            new FingerprintTracker(
                sm.logger(),
                event.getServer()
                    .getFile("config"),
                event.getServer()
                    .getFile("scripts")).start();

            if (selftest && !selftestDir.isEmpty()) {
                LOG.warn("SELFTEST MODE ACTIVE — this server run is a harness scenario, not a real session");
                final List<Object> auditListeners = new ArrayList<>(forgeBusListeners);
                auditListeners.addAll(fmlBusListeners);
                onFmlBus(new SelfTest(sm, Paths.get(selftestDir), auditListeners));
            }
        } catch (IOException | RuntimeException e) { // noqa: NAR142, degrade and report over the trackers of pack mods
                                                     // at server start
            // a broken audit trail must be loud, but the server of the run must keep running
            LOG.error("Speedrun audit failed to start — THIS RUN IS NOT BEING AUDITED", e);
            session = null;
            unregisterAuditListeners();
            if (started != null) {
                started.stopped();
            }
        }
    }

    /**
     * Log the item ID table and the quest database once every mod's serverStarting handler has run. BetterQuesting
     * loads its quest book in serverStarting.
     */
    @Mod.EventHandler
    public void serverStarted(FMLServerStartedEvent event) {
        final SessionManager sm = session;
        if (sm == null) {
            return;
        }
        final MinecraftServer server = MinecraftServer.getServer();
        try {
            ItemIdTable.log(
                sm.logger(),
                sm.anchor(),
                sm.logger()
                    .clock()
                    .get());
        } catch (RuntimeException | LinkageError e) { // noqa: NAR142, degrade and report over the item registry of
                                                      // every mod
            LOG.error("Item ID table not recorded", e);
        }
        if (Compat.BETTER_QUESTING.isLoaded()) {
            BqQuestDb.log(sm.logger(), server);
        }
    }

    private void onForgeBus(Object listener) {
        MinecraftForge.EVENT_BUS.register(listener);
        forgeBusListeners.add(listener);
    }

    private void onFmlBus(Object listener) {
        FMLCommonHandler.instance()
            .bus()
            .register(listener);
        fmlBusListeners.add(listener);
    }

    @Mod.EventHandler
    public void serverStopping(FMLServerStoppingEvent event) {
        final SessionManager sm = session;
        if (sm != null) {
            // The shutdown save runs after this event and skips every world in savingOffDims. With dim 0 in
            // savingOffDims the anchor is not saved, and the next start is classified as WORLD_ROLLBACK.
            final JsonObject savingFields = new JsonObject();
            WorldSaving.addSavingFields(sm.server(), savingFields, BACKUPS);
            sm.stopping(savingFields);
        }
    }

    @Mod.EventHandler
    public void serverStopped(FMLServerStoppedEvent event) {
        final SessionManager sm = session;
        session = null;
        if (sm != null) {
            sm.stopped();
        }
        unregisterAuditListeners();
    }

    private void unregisterAuditListeners() {
        for (Object listener : forgeBusListeners) {
            MinecraftForge.EVENT_BUS.unregister(listener);
        }
        for (Object listener : fmlBusListeners) {
            FMLCommonHandler.instance()
                .bus()
                .unregister(listener);
        }
        forgeBusListeners.clear();
        fmlBusListeners.clear();
        if (worldEditStop != null) {
            worldEditStop.run();
            worldEditStop = null;
        }
    }
}
