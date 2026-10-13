package com.gtnhspeedrun.audit;

import java.util.ArrayList;
import java.util.List;

import net.minecraftforge.common.MinecraftForge;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.gtnhspeedrun.audit.command.CommandAudit;
import com.gtnhspeedrun.audit.core.SessionManager;
import com.gtnhspeedrun.audit.snapshot.InventorySnapshotter;
import com.gtnhspeedrun.audit.snapshot.KeyItemIndex;
import com.gtnhspeedrun.audit.snapshot.SnapshotScheduler;
import com.gtnhspeedrun.audit.trackers.CommandTracker;
import com.gtnhspeedrun.audit.trackers.GamemodeTracker;
import com.gtnhspeedrun.audit.trackers.MilestoneTracker;
import com.gtnhspeedrun.audit.trackers.PlayerSessionTracker;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStartedEvent;
import cpw.mods.fml.common.event.FMLServerStartingEvent;
import cpw.mods.fml.common.event.FMLServerStoppedEvent;
import cpw.mods.fml.common.event.FMLServerStoppingEvent;

/**
 * Speedrun audit trail for GT: New Horizons. All logic is server-side; in singleplayer the integrated server
 * runs it identically, which is what makes one jar cover both setups. Clients without the mod may join an
 * audited server (acceptableRemoteVersions="*") — auditing is the server's job.
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

    /** The live session, or null between worlds. Static because mixin sinks have no other path to it. */
    private static volatile SessionManager session;

    /** Both buses tolerate listeners whose events belong to the other bus, so one list per bus suffices. */
    private final List<Object> forgeBusListeners = new ArrayList<>();
    private final List<Object> fmlBusListeners = new ArrayList<>();
    /**
     * Unregisters from WorldEdit's own event bus; null without WorldEdit. A Runnable keeps WE types out of this class.
     */
    private Runnable worldEditStop;

    public static SessionManager session() {
        return session;
    }

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        AuditConfig.synchronizeConfiguration(event.getSuggestedConfigurationFile());
    }

    @Mod.EventHandler
    public void serverStarting(FMLServerStartingEvent event) {
        try {
            // The self-test shapes its own preconditions: it needs a watched key item to assert on.
            if (System.getProperty(com.gtnhspeedrun.audit.selftest.SelfTest.PROPERTY) != null) {
                AuditConfig.keyItems = new String[] { "minecraft:diamond" };
                AuditConfig.trackedPlacements = new String[] { "minecraft:diamond_block" };
                AuditConfig.watchedBlocks = new String[] { "minecraft:diamond_block" };
                AuditConfig.nodeCensusTiles = new String[] { "net.minecraft.tileentity.TileEntityChest" };
            }
            final SessionManager sm = new SessionManager(LOG, event.getServer());
            sm.start();
            session = sm;

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
            final boolean selftest = System.getProperty(com.gtnhspeedrun.audit.selftest.SelfTest.PROPERTY) != null;
            final com.gtnhspeedrun.audit.trackers.MachinePlacementTracker placements = new com.gtnhspeedrun.audit.trackers.MachinePlacementTracker(
                sm.logger(),
                nonBlank(AuditConfig.trackedPlacements),
                nonBlank(AuditConfig.watchedBlocks),
                selftest ? com.gtnhspeedrun.audit.selftest.SelfTest.STAND_IN_MACHINE_BLOCK
                    : com.gtnhspeedrun.audit.trackers.MachinePlacementTracker.GT_MACHINE_BLOCK,
                selftest ? com.gtnhspeedrun.audit.selftest.SelfTest.STAND_IN_MANIPULATOR_PREFIX
                    : com.gtnhspeedrun.audit.trackers.MachinePlacementTracker.MANIPULATOR_PREFIX);
            onForgeBus(placements); // PlaceEvent, BreakEvent
            onFmlBus(placements); // end of tick: Matter Manipulator placements
            onFmlBus(inventory); // login/logout snapshots
            onFmlBus(milestones); // dim change, crafts
            onFmlBus(new PlayerSessionTracker(sm.logger(), AuditConfig.logPlayerIp, AuditConfig.flagClientMods));
            onFmlBus(new GamemodeTracker(sm.logger(), inventory, event.getServer()));
            onFmlBus(new com.gtnhspeedrun.audit.trackers.DifficultyTracker(sm.logger(), event.getServer()));
            onFmlBus(new com.gtnhspeedrun.audit.trackers.GameruleTracker(sm.logger(), event.getServer()));
            onFmlBus(scheduler);

            // The compat classes reference modded types — only touch them behind the presence gate.
            if (com.gtnhspeedrun.audit.compat.Compat.BETTER_QUESTING.isLoaded()) {
                onForgeBus(new com.gtnhspeedrun.audit.compat.BqQuestTracker(sm.logger()));
            }
            if (cpw.mods.fml.common.Loader.isModLoaded("Thaumcraft")
                || cpw.mods.fml.common.Loader.isModLoaded("ThaumicHorizons")
                || System.getProperty(com.gtnhspeedrun.audit.selftest.SelfTest.PROPERTY) != null) {
                final com.gtnhspeedrun.audit.snapshot.NodeCensus nodes = new com.gtnhspeedrun.audit.snapshot.NodeCensus(
                    sm.logger(),
                    event.getServer(),
                    AuditConfig.nodeCensusTiles,
                    AuditConfig.nodeCensusMinutes);
                onFmlBus(nodes);
                sm.addSnapshotHook(() -> nodes.census("manual"));
            }
            if (com.gtnhspeedrun.audit.snapshot.StorageCensus.anythingToCensus()) {
                final com.gtnhspeedrun.audit.snapshot.StorageCensus storage = new com.gtnhspeedrun.audit.snapshot.StorageCensus(
                    sm.logger(),
                    AuditConfig.storageCensusMinutes);
                onFmlBus(storage);
                sm.addSnapshotHook(() -> storage.census("manual"));
            }
            // Vanilla types only; without ServerUtilities it never sees a view.
            onFmlBus(new com.gtnhspeedrun.audit.trackers.InvseeViews()); // target logins, viewer logouts
            // Vanilla types only; without ServerUtilities it simply never sees a trash chest.
            onFmlBus(new com.gtnhspeedrun.audit.trackers.TrashCanCommandTracker(sm.logger()));
            if (com.gtnhspeedrun.audit.compat.Compat.GT5.isLoaded()) {
                final com.gtnhspeedrun.audit.compat.GtWirelessAudit wireless = new com.gtnhspeedrun.audit.compat.GtWirelessAudit(
                    sm.logger());
                onForgeBus(wireless); // CommandEvent
                onFmlBus(wireless); // end-of-tick balance read
                final com.gtnhspeedrun.audit.compat.GtPowerCensus power = new com.gtnhspeedrun.audit.compat.GtPowerCensus(
                    sm.logger(),
                    event.getServer(),
                    AuditConfig.powerCensusClasses,
                    AuditConfig.powerCensusMinutes);
                onFmlBus(power);
                sm.addSnapshotHook(() -> power.census("manual"));
            }
            if (com.gtnhspeedrun.audit.compat.Compat.AE2.isLoaded()) {
                final com.gtnhspeedrun.audit.compat.Ae2Snapshotter ae2 = new com.gtnhspeedrun.audit.compat.Ae2Snapshotter(
                    sm.logger(),
                    keyItems,
                    event.getServer(),
                    AuditConfig.ae2SnapshotActiveMinutes,
                    AuditConfig.ae2SnapshotIdleMinutes);
                onFmlBus(ae2);
                sm.addSnapshotHook(ae2::requestCensus);

                final String benchDir = System.getProperty(com.gtnhspeedrun.audit.compat.Ae2Bench.PROPERTY);
                if (benchDir != null && !benchDir.isEmpty()) {
                    LOG.warn("AE2 BENCH MODE ACTIVE — this server run measures the census and then shuts down");
                    onFmlBus(new com.gtnhspeedrun.audit.compat.Ae2Bench(event.getServer(), new java.io.File(benchDir)));
                }
            }

            if (com.gtnhspeedrun.audit.compat.Compat.WORLDEDIT.isLoaded()) {
                try {
                    final com.gtnhspeedrun.audit.compat.WorldEditAudit worldEdit = new com.gtnhspeedrun.audit.compat.WorldEditAudit(
                        sm.logger());
                    worldEdit.register();
                    onFmlBus(worldEdit); // end of tick: one worldedit_edit per edit session
                    worldEditStop = worldEdit::unregister;
                } catch (LinkageError | RuntimeException e) {
                    // A WorldEdit build with a different API: everything else is still audited.
                    LOG.error("WorldEdit edits NOT audited — WorldEdit API mismatch", e);
                }
            }

            event.registerServerCommand(new CommandAudit());

            new com.gtnhspeedrun.audit.trackers.FingerprintTracker(
                sm.logger(),
                event.getServer()
                    .getFile("config"),
                event.getServer()
                    .getFile("scripts")).start();

            final String selftestDir = System.getProperty(com.gtnhspeedrun.audit.selftest.SelfTest.PROPERTY);
            if (selftestDir != null && !selftestDir.isEmpty()) {
                LOG.warn("SELFTEST MODE ACTIVE — this server run is a harness scenario, not a real session");
                final List<Object> auditListeners = new ArrayList<>(forgeBusListeners);
                auditListeners.addAll(fmlBusListeners);
                onFmlBus(
                    new com.gtnhspeedrun.audit.selftest.SelfTest(
                        event.getServer(),
                        sm,
                        new java.io.File(selftestDir),
                        auditListeners));
            }
        } catch (Exception e) {
            // A broken audit trail must be loud but must not brick someone's server mid-run.
            LOG.error("Speedrun audit failed to start — THIS RUN IS NOT BEING AUDITED", e);
            session = null;
        }
    }

    /** Records that need every mod's serverStarting to have run first (BQ loads its quest book there). */
    @Mod.EventHandler
    public void serverStarted(FMLServerStartedEvent event) {
        final SessionManager sm = session;
        if (sm == null) {
            return;
        }
        final net.minecraft.server.MinecraftServer server = net.minecraft.server.MinecraftServer.getServer();
        try {
            com.gtnhspeedrun.audit.trackers.ItemIdTable.log(
                sm.logger(),
                sm.anchor(),
                sm.logger()
                    .clock()
                    .get());
        } catch (RuntimeException | LinkageError e) {
            LOG.error("Item ID table not recorded", e);
        }
        if (com.gtnhspeedrun.audit.compat.Compat.BETTER_QUESTING.isLoaded()) {
            com.gtnhspeedrun.audit.compat.BqQuestDb.log(sm.logger(), server);
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
            sm.stopping();
        }
    }

    @Mod.EventHandler
    public void serverStopped(FMLServerStoppedEvent event) {
        final SessionManager sm = session;
        session = null;
        if (sm != null) {
            sm.stopped();
        }
        for (Object l : forgeBusListeners) {
            MinecraftForge.EVENT_BUS.unregister(l);
        }
        for (Object l : fmlBusListeners) {
            FMLCommonHandler.instance()
                .bus()
                .unregister(l);
        }
        forgeBusListeners.clear();
        fmlBusListeners.clear();
        if (worldEditStop != null) {
            worldEditStop.run();
            worldEditStop = null;
        }
    }

    private static java.util.Set<String> nonBlank(String[] entries) {
        final java.util.Set<String> out = new java.util.HashSet<>();
        for (String entry : entries) {
            if (!entry.trim()
                .isEmpty()) {
                out.add(entry.trim());
            }
        }
        return out;
    }
}
