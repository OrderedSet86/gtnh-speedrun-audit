package com.gtnhspeedrun.audit.trackers;

import net.minecraft.tileentity.TileEntity;

import com.google.gson.JsonObject;
import com.gtnhspeedrun.audit.GtnhSpeedrunAudit;
import com.gtnhspeedrun.audit.core.SessionManager;

/**
 * Log a {@code machine_facing} line for each turn of a GT machine to a new front facing, such as a wrench rotation. The
 * placement's facing is on the {@code machine_placed} or {@code bulk_placed} line.
 *
 * <pre>
 *     {"dim":0,"x":176,"y":107,"z":-124,"machineId":1,"machine":"Bronze Hull","machineKey":"gt.blockmachines.hull.bronze",
 *      "from":"SOUTH","to":"NORTH"}
 * </pre>
 */
public final class FacingSinks {

    private FacingSinks() {}

    public static void logFacingChange(TileEntity tile, String from, String to) {
        final SessionManager session = GtnhSpeedrunAudit.session();
        if (session == null) {
            return;
        }
        final JsonObject data = new JsonObject();
        data.addProperty("dim", tile.getWorldObj().provider.dimensionId);
        data.addProperty("x", tile.xCoord);
        data.addProperty("y", tile.yCoord);
        data.addProperty("z", tile.zCoord);
        // getDamageValue() of GT's machine block returns the tile entity's meta tile ID
        final int machineId = tile.getBlockType()
            .getDamageValue(tile.getWorldObj(), tile.xCoord, tile.yCoord, tile.zCoord);
        MachinePlacementTracker.readGtMachine(tile.getBlockType(), machineId, tile)
            .writeTo(data);
        data.addProperty("from", from);
        data.addProperty("to", to);
        session.logger()
            .log("machine_facing", data);
    }
}
