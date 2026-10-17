package com.gtnhspeedrun.audit.trackers;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

/**
 * Where a formed multiblock sits: the box of the positions its structure check visited (StructureBounds) and the
 * controller's facing. rotation and flip are null for a controller that is not a StructureLib IAlignment.
 */
public final class MultiblockFootprint {

    /** {minX, minY, minZ, maxX, maxY, maxZ}, inclusive. Null when the check visited nothing through StructureLib. */
    public final int[] bounds;
    public final String facing;
    public final String rotation;
    public final String flip;

    public MultiblockFootprint(int[] bounds, String facing, String rotation, String flip) {
        this.bounds = bounds;
        this.facing = facing;
        this.rotation = rotation;
        this.flip = flip;
    }

    /** The controller's NBT keeps this key, so a footprint is logged again only when it changes. */
    public String key() {
        final StringBuilder sb = new StringBuilder();
        for (int v : bounds) {
            sb.append(v)
                .append(',');
        }
        return sb.append(facing)
            .append('/')
            .append(rotation)
            .append('/')
            .append(flip)
            .toString();
    }

    void writeTo(JsonObject data) {
        if (bounds != null) {
            final JsonArray box = new JsonArray();
            for (int v : bounds) {
                box.add(new JsonPrimitive(v));
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
    }
}
