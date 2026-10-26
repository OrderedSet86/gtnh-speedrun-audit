package com.gtnhspeedrun.audit.trackers;

import org.jetbrains.annotations.Nullable;

import com.google.gson.JsonObject;

/**
 * Name what removed a GT machine block, and the player when a player removed the block.
 *
 * @param remover null for a removal with no player, such as an explosion
 */
public record RemovalCause(Cause cause, @Nullable Remover remover) {

    /** The cause of a removal with no noted cause, such as a command, a WorldEdit edit or another mod's code. */
    public static final RemovalCause OTHER = ofWorld(Cause.OTHER);

    public static RemovalCause ofWorld(Cause cause) {
        return new RemovalCause(cause, null);
    }

    /** Write {@code cause}, then {@code uuid}, {@code name} and {@code tool} when a player removed the block. */
    public void writeTo(JsonObject data) {
        data.addProperty("cause", cause.code);
        if (remover == null) {
            return;
        }
        data.addProperty("uuid", remover.uuid());
        data.addProperty("name", remover.playerName());
        if (remover.toolKey() != null) {
            data.addProperty("tool", remover.toolKey());
        }
    }

    /**
     * The player who removed a block, and the item in the player's main hand.
     *
     * @param toolKey  the item's key, such as {@code minecraft:diamond_pickaxe@0}, or null for an empty hand
     * @param toolName the item's display name in the server's language, or null for an empty hand
     */
    public record Remover(String uuid, String playerName, @Nullable String toolKey, @Nullable String toolName) {}

    /** One kind of removal. {@code code} is the value in the log. */
    public enum Cause {

        BROKEN("broken", false),
        WRENCHED("wrenched", false),
        VAJRA("vajra", false),
        EXPLODED("exploded", false),
        BURNED("burned", false),
        // the Matter Manipulator removes many blocks in one tick, so the manipulator's removals go in one
        // bulk_removed line
        MANIPULATOR("manipulator", true),
        OTHER("other", false);

        public final String code;
        public final boolean batched;

        Cause(String code, boolean batched) {
            this.code = code;
            this.batched = batched;
        }
    }
}
