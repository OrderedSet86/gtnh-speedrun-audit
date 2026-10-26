package com.gtnhspeedrun.audit.core;

import javax.annotation.Nullable;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** The anchor values that tell one saved copy of the world from another: session_start.anchor and lastSave. */
public record AnchorValues(long seq, long ticks, long wallMs) {

    public static AnchorValues readFrom(ChainAnchorData anchor) {
        return new AnchorValues(anchor.lastSeq, anchor.cumulativeTicks, anchor.lastWallMs);
    }

    /** Return the values of a {@link #toJson()} object, or null when {@code rawValues} lacks a number. */
    @Nullable
    public static AnchorValues parseAnchorValues(@Nullable JsonElement rawValues) {
        if (!(rawValues != null && rawValues.isJsonObject()
            && JsonUtil.hasNumber(rawValues.getAsJsonObject(), "seq")
            && JsonUtil.hasNumber(rawValues.getAsJsonObject(), "ticks")
            && JsonUtil.hasNumber(rawValues.getAsJsonObject(), "wall"))) {
            return null;
        }
        final JsonObject values = rawValues.getAsJsonObject();
        return new AnchorValues(
            values.get("seq")
                .getAsLong(),
            values.get("ticks")
                .getAsLong(),
            values.get("wall")
                .getAsLong());
    }

    public JsonObject toJson() {
        final JsonObject values = new JsonObject();
        values.addProperty("seq", seq);
        values.addProperty("ticks", ticks);
        values.addProperty("wall", wallMs);
        return values;
    }
}
