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
            onForgeBus(new com.gtnhspeedrun.audit.trackers.MachinePlacementTracker(sm.logger()));
            onFmlBus(inventory); // login/logout snapshots
            onFmlBus(milestones); // dim change, crafts
            onFmlBus(new PlayerSessionTracker(sm.logger(), AuditConfig.logPlayerIp));
            onFmlBus(new GamemodeTracker(sm.logger(), inventory, event.getServer()));
            onFmlBus(scheduler);

            // The compat classes reference modded types — only touch them behind the presence gate.
            if (com.gtnhspeedrun.audit.compat.Compat.BETTER_QUESTING.isLoaded()) {
                onForgeBus(new com.gtnhspeedrun.audit.compat.BqQuestTracker(sm.logger()));
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
            }

            event.registerServerCommand(new CommandAudit());

            new com.gtnhspeedrun.audit.trackers.FingerprintTracker(
                sm.logger(),
                sm.auditDir(),
                event.getServer()
                    .getFile("config"),
                event.getServer()
                    .getFile("scripts")).start();
        } catch (Exception e) {
            // A broken audit trail must be loud but must not brick someone's server mid-run.
            LOG.error("Speedrun audit failed to start — THIS RUN IS NOT BEING AUDITED", e);
            session = null;
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
    }
}
