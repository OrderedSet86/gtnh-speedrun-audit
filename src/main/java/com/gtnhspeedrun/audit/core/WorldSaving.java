package com.gtnhspeedrun.audit.core;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.DimensionManager;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.gtnhspeedrun.audit.compat.BackupAccess;

/**
 * Whether worlds are saving. While WorldServer.levelSaving is set, autosaves and the shutdown save both skip that
 * world (ChunkProviderServer.canSave), so a stop leaves it as of its last save and the next start reads
 * WORLD_ROLLBACK. ServerUtilities sets the flag on every world for the length of each backup; /save-off sets it
 * too.
 *
 * <p>
 * The overworld decides: the chain anchor is saved with it. Some worlds never save (Gadomancy's Outer
 * Lands, dim 173), so "any world off" would be true all session.
 */
final class WorldSaving {

    private WorldSaving() {}

    static boolean overworldOff() {
        final WorldServer overworld = DimensionManager.getWorld(0);
        return overworld != null && overworld.levelSaving;
    }

    /** The saving state as log fields: savingOffDims (empty when every world saves) and backupRunning. */
    static void describe(MinecraftServer server, JsonObject data) {
        final JsonArray off = new JsonArray();
        for (WorldServer world : server.worldServers) {
            if (world != null && world.levelSaving) {
                off.add(new JsonPrimitive(world.provider.dimensionId));
            }
        }
        data.add("savingOffDims", off);
        final Boolean backup = BackupAccess.running();
        if (backup != null) {
            data.addProperty("backupRunning", backup);
        }
    }
}
