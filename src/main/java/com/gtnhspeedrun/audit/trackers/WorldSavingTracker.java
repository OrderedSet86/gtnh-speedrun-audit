package com.gtnhspeedrun.audit.trackers;

import net.minecraft.server.MinecraftServer;
import net.minecraftforge.event.world.WorldEvent;

import com.google.gson.JsonObject;
import com.gtnhspeedrun.audit.core.AnchorValues;
import com.gtnhspeedrun.audit.core.AuditLogger;
import com.gtnhspeedrun.audit.core.ChainAnchorData;
import com.gtnhspeedrun.audit.core.SessionManager;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;

/**
 * Log when overworld saving turns off or on, and when a ServerUtilities backup finishes. The session's tick calls
 * {@link #checkWorldSaving()}, so these lines keep their place in the tick, before timing_started and before the run
 * clocks advance.
 */
public final class WorldSavingTracker {

    private final AuditLogger logger;
    private final ChainAnchorData anchor;
    private final MinecraftServer server;
    private final BackupProbe backups;
    private boolean savingWasOff;
    private Boolean backupWasRunning;
    // null until the first overworld save of this session
    private AnchorValues lastSavedAnchor;

    public WorldSavingTracker(SessionManager session, BackupProbe backups) {
        this.logger = session.logger();
        this.anchor = session.anchor();
        this.server = session.server();
        this.backups = backups;
    }

    /**
     * Log world_saving when overworld saving turns off, as at the start of a backup, and when overworld saving turns
     * back on. Log backup_done when a ServerUtilities backup thread finishes.
     *
     * <p>
     * After the first overworld save of the session, the off line has {@code lastSave}: the anchor values that the
     * latest overworld save wrote. A backup saves every world and then turns saving off, so these values are the
     * anchor inside the zip of that backup. A restore of that backup starts with session_start.anchor equal to
     * lastSave, so a reader can tell which backup came back.
     */
    public void checkWorldSaving() {
        final boolean off = WorldSaving.isOverworldSavingOff();
        if (off != savingWasOff) {
            savingWasOff = off;
            final JsonObject data = new JsonObject();
            data.addProperty("enabled", !off);
            WorldSaving.addSavingFields(server, data, backups);
            if (off && lastSavedAnchor != null) {
                data.add("lastSave", lastSavedAnchor.toJson());
            }
            logger.log("world_saving", data);
        }
        final Boolean running = backups.isRunning()
            .get();
        if (Boolean.TRUE.equals(backupWasRunning) && Boolean.FALSE.equals(running)) {
            final JsonObject data = new JsonObject();
            backups.describeNewest()
                .accept(data);
            logger.log("backup_done", data);
        }
        backupWasRunning = running;
    }

    /**
     * Record the anchor values that the latest overworld save wrote, for lastSave on the world_saving line. Forge
     * posts WorldEvent.Save after saveLevel() wrote the map storage of the overworld, the anchor included.
     * saveLevel() and the post are in one save call on the server thread, so SessionManager.publishAnchor() can't run
     * between them. The anchor fields read here are the values that the save wrote.
     */
    @SubscribeEvent
    public void onWorldSave(WorldEvent.Save event) {
        if (!(!event.world.isRemote && event.world.provider.dimensionId == 0)) {
            return;
        }
        lastSavedAnchor = AnchorValues.readFrom(anchor);
    }
}
