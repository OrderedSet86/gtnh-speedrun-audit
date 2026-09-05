package com.gtnhspeedrun.audit;

import net.minecraftforge.common.MinecraftForge;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.gtnhspeedrun.audit.core.SessionManager;
import com.gtnhspeedrun.audit.trackers.CommandTracker;
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

    private CommandTracker commandTracker;
    private PlayerSessionTracker playerTracker;

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

            commandTracker = new CommandTracker(sm.logger());
            playerTracker = new PlayerSessionTracker(sm.logger(), AuditConfig.logPlayerIp);
            MinecraftForge.EVENT_BUS.register(commandTracker);
            FMLCommonHandler.instance().bus().register(playerTracker);
        } catch (Exception e) {
            // A broken audit trail must be loud but must not brick someone's server mid-run.
            LOG.error("Speedrun audit failed to start — THIS RUN IS NOT BEING AUDITED", e);
            session = null;
        }
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
        if (commandTracker != null) {
            MinecraftForge.EVENT_BUS.unregister(commandTracker);
            commandTracker = null;
        }
        if (playerTracker != null) {
            FMLCommonHandler.instance().bus().unregister(playerTracker);
            playerTracker = null;
        }
    }
}
