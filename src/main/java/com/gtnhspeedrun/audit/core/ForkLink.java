package com.gtnhspeedrun.audit.core;

import javax.annotation.Nullable;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * The save of a parent chain that a world copy started from. The copy's chain starts at seq 0 with a new audit UUID.
 * The fork block of the copy's first session_start has this link.
 *
 * @param parentSave the anchor values of the copied save. {@code parentSave.seq()} is the parent's last line in the
 *                   save, and {@code parentHash} is the hash of that line
 */
public record ForkLink(String parentUuid, String parentFolder, AnchorValues parentSave, String parentHash) {

    /** Return the link in a fork block, or null when {@code rawFork} lacks a field. */
    @Nullable
    public static ForkLink parseForkLink(@Nullable JsonElement rawFork) {
        if (!(rawFork != null && rawFork.isJsonObject()
            && JsonUtil.hasString(rawFork.getAsJsonObject(), "parentUuid")
            && JsonUtil.hasString(rawFork.getAsJsonObject(), "parentFolder")
            && JsonUtil.hasString(rawFork.getAsJsonObject(), "parentHash")
            && JsonUtil.hasNumber(rawFork.getAsJsonObject(), "parentSeq")
            && JsonUtil.hasNumber(rawFork.getAsJsonObject(), "parentTicks")
            && JsonUtil.hasNumber(rawFork.getAsJsonObject(), "parentWall"))) {
            return null;
        }
        final JsonObject fork = rawFork.getAsJsonObject();
        return new ForkLink(
            fork.get("parentUuid")
                .getAsString(),
            fork.get("parentFolder")
                .getAsString(),
            new AnchorValues(
                fork.get("parentSeq")
                    .getAsLong(),
                fork.get("parentTicks")
                    .getAsLong(),
                fork.get("parentWall")
                    .getAsLong()),
            fork.get("parentHash")
                .getAsString());
    }

    /** Build the link fields of the fork block. The run analyzer reads these keys, so NEVER rename one. */
    public JsonObject toJson() {
        final JsonObject fork = new JsonObject();
        fork.addProperty("parentUuid", parentUuid);
        fork.addProperty("parentFolder", parentFolder);
        fork.addProperty("parentSeq", parentSave.seq());
        fork.addProperty("parentHash", parentHash);
        fork.addProperty("parentTicks", parentSave.ticks());
        fork.addProperty("parentWall", parentSave.wallMs());
        return fork;
    }
}
