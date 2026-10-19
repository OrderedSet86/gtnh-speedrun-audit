package com.gtnhspeedrun.audit.trackers;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.DimensionManager;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

/**
 * Report whether worlds are saving. The MCP name WorldServer.levelSaving is inverted: while the flag is set,
 * autosaves and the shutdown save skip that world (ChunkProviderServer.canSave()). A stop then leaves the world as of
 * its last save, and the next start is classified as WORLD_ROLLBACK. ServerUtilities sets the flag on every world for
 * the length of each backup. The flag is also set by /save-off.
 *
 * <p>
 * Only the overworld counts, because the chain anchor is saved with the overworld. Some worlds never save, such as
 * the Outer Lands of Gadomancy (dim 173), so "any world off" would be true for the whole session.
 */
public final class WorldSaving {

    private WorldSaving() {}

    public static boolean isOverworldSavingOff() {
        final WorldServer overworld = DimensionManager.getWorld(0);
        return overworld != null && overworld.levelSaving;
    }

    /**
     * Add savingOffDims and backupRunning. savingOffDims is empty when every world saves, and backupRunning is absent
     * when the backup state is unknown.
     */
    public static void addSavingFields(MinecraftServer server, JsonObject data, BackupProbe backups) {
        final JsonArray offDims = new JsonArray();
        for (WorldServer world : server.worldServers) {
            if (world != null && world.levelSaving) {
                offDims.add(new JsonPrimitive(world.provider.dimensionId));
            }
        }
        data.add("savingOffDims", offDims);
        final Boolean backupRunning = backups.isRunning()
            .get();
        if (backupRunning != null) {
            data.addProperty("backupRunning", backupRunning);
        }
    }
}
