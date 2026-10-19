package com.gtnhspeedrun.audit.trackers;

import net.minecraft.tileentity.TileEntity;

import org.jetbrains.annotations.Nullable;

import com.google.gson.JsonObject;

/**
 * Name the machine that a ledger line is about, and the machine's position, with no modded type in the signature.
 *
 * @param className the machine's simple class name
 * @param localName GT's local name key, or null for a machine with no such key
 */
public record MachineSite(String className, @Nullable String localName, int dim, int x, int y, int z) {

    /** Read the site of a tile entity with no local name, such as a Railcraft multiblock or a Blood Altar. */
    public static MachineSite ofTile(TileEntity tile) {
        return new MachineSite(
            tile.getClass()
                .getSimpleName(),
            null,
            tile.getWorldObj().provider.dimensionId,
            tile.xCoord,
            tile.yCoord,
            tile.zCoord);
    }

    /** Return the session dedupe key: the class name, then the dimension and the position. */
    public String formatKey() {
        return className + "@" + dim + ":" + x + "," + y + "," + z;
    }

    /** Write {@code class}, then {@code localName} when there is one, then the position. */
    public void writeTo(JsonObject data) {
        data.addProperty("class", className);
        if (localName != null) {
            data.addProperty("localName", localName);
        }
        writePositionTo(data);
    }

    public void writePositionTo(JsonObject data) {
        data.addProperty("dim", dim);
        data.addProperty("x", x);
        data.addProperty("y", y);
        data.addProperty("z", z);
    }
}
