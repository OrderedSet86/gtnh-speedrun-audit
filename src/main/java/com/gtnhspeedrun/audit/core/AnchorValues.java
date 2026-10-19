package com.gtnhspeedrun.audit.core;

import com.google.gson.JsonObject;

/** The anchor values that tell one saved copy of the world from another: session_start.anchor and lastSave. */
public record AnchorValues(long seq, long ticks, long wallMs) {

    public static AnchorValues readFrom(ChainAnchorData anchor) {
        return new AnchorValues(anchor.lastSeq, anchor.cumulativeTicks, anchor.lastWallMs);
    }

    public JsonObject toJson() {
        final JsonObject values = new JsonObject();
        values.addProperty("seq", seq);
        values.addProperty("ticks", ticks);
        values.addProperty("wall", wallMs);
        return values;
    }
}
