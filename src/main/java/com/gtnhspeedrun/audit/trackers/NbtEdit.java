package com.gtnhspeedrun.audit.trackers;

import net.minecraft.entity.player.EntityPlayer;

import org.jetbrains.annotations.Nullable;

import com.google.gson.JsonObject;
import com.gtnhspeedrun.audit.GtnhSpeedrunAudit;
import com.gtnhspeedrun.audit.core.SessionManager;

/**
 * Build an {@code nbt_edit} line for the applied half of /nbtedit: the packet whose NBT ServerUtilities writes into
 * a player, block, entity or item. The dispatch is in the {@code command} line.
 *
 * <p>
 * The line has the payload's hash, and the full payload unless the payload is oversized, so verifiers can see what
 * was set. The target's NBT just before the write is logged the same way, so verifiers can see what the write
 * replaced. Without {@link #before}, the line has the reason from {@link #beforeMissing}, such as an unloaded
 * chunk or a missing tile entity.
 */
public final class NbtEdit {

    private final String targetType;
    private final String target;
    private String uuid;
    private String name;
    private String nbtHash;
    private String nbtB64;
    private String beforeHash;
    private String beforeB64;
    private String beforeMissing;

    private NbtEdit(String targetType, String target) {
        this.targetType = targetType;
        this.target = target;
    }

    public static NbtEdit of(String targetType, String target) {
        return new NbtEdit(targetType, target);
    }

    public NbtEdit player(EntityPlayer player) {
        uuid = player.getGameProfile()
            .getId()
            .toString();
        name = player.getCommandSenderName();
        return this;
    }

    /** @param b64 null when the payload is oversized */
    public NbtEdit written(String hash, @Nullable String b64) {
        nbtHash = hash;
        nbtB64 = b64;
        return this;
    }

    /** @param b64 null when the NBT is oversized */
    public NbtEdit before(String hash, @Nullable String b64) {
        beforeHash = hash;
        beforeB64 = b64;
        return this;
    }

    public NbtEdit beforeMissing(@Nullable String reason) {
        beforeMissing = reason;
        return this;
    }

    /** Write the line when a session is logging. A null Base64, before-hash or reason is left off the line. */
    public void log() {
        final SessionManager session = GtnhSpeedrunAudit.session();
        if (session == null) {
            return;
        }
        final JsonObject data = new JsonObject();
        data.addProperty("targetType", targetType);
        data.addProperty("target", target);
        data.addProperty("uuid", uuid);
        data.addProperty("name", name);
        data.addProperty("nbtHash", nbtHash);
        if (nbtB64 != null) {
            data.addProperty("nbtB64", nbtB64);
        }
        if (beforeHash != null) {
            data.addProperty("beforeHash", beforeHash);
        }
        if (beforeB64 != null) {
            data.addProperty("beforeB64", beforeB64);
        }
        if (beforeMissing != null) {
            data.addProperty("beforeMissing", beforeMissing);
        }
        session.logger()
            .log("nbt_edit", data);
    }
}
