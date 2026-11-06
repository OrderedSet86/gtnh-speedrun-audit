package com.gtnhspeedrun.audit.trackers;

import org.jetbrains.annotations.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.gtnhspeedrun.audit.trackers.ExtentCells.Extents;

/**
 * Describe where a formed multiblock is: the box of the positions that the structure check of the multiblock visited,
 * from {@link StructureBounds}, the controller's facing, and the blocks at those positions, encoded by
 * {@link ExtentCells}.
 */
public final class MultiblockFootprint {

    /** {minX, minY, minZ, maxX, maxY, maxZ}, inclusive. Null when the check visited nothing through StructureLib. */
    public final int[] bounds;
    public final String facing;
    public final String rotation;
    public final String flip;
    // StructureBounds.computeExtentHash() of the check, 0 for no positions
    private long extentHash;
    private @Nullable Extents extents;

    /** @param alignment null for a controller that is not a StructureLib {@code IAlignment} */
    public MultiblockFootprint(int[] bounds, String facing, @Nullable Alignment alignment) {
        this.bounds = bounds;
        this.facing = facing;
        this.rotation = alignment == null ? null : alignment.rotation();
        this.flip = alignment == null ? null : alignment.flip();
    }

    /** Describe a controller that is not a StructureLib {@code IAlignment}, and so has only a front facing. */
    public static MultiblockFootprint ofFacing(int[] bounds, String facing) {
        return new MultiblockFootprint(bounds, facing, null);
    }

    public void setExtentHash(long extentHash) {
        this.extentHash = extentHash;
    }

    /** Set the blocks that {@link #writeTo} writes. Encode the blocks only for a footprint that is logged. */
    public void setExtents(@Nullable Extents extents) {
        this.extents = extents;
    }

    public boolean hasExtents() {
        return extents != null;
    }

    /**
     * Return the dedupe key. The key of the last logged footprint is saved in the controller's NBT, so a footprint is
     * logged again only when the key changes.
     */
    public String formatKey() {
        final StringBuilder key = new StringBuilder();
        for (int coordinate : bounds) {
            key.append(coordinate)
                .append(',');
        }
        return key.append(facing)
            .append('/')
            .append(rotation)
            .append('/')
            .append(flip)
            .append('#')
            .append(Long.toHexString(extentHash))
            .toString();
    }

    public void writeTo(JsonObject data) {
        if (bounds != null) {
            final JsonArray box = new JsonArray();
            for (int coordinate : bounds) {
                box.add(new JsonPrimitive(coordinate));
            }
            data.add("bounds", box);
        }
        if (facing != null) {
            data.addProperty("facing", facing);
        }
        if (rotation != null) {
            data.addProperty("rotation", rotation);
        }
        if (flip != null) {
            data.addProperty("flip", flip);
        }
        if (extents != null) {
            extents.writeTo(data);
        }
    }

    /** The rotation and the flip of a StructureLib {@code IAlignment} controller, by enum constant name. */
    public record Alignment(String rotation, String flip) {}
}
