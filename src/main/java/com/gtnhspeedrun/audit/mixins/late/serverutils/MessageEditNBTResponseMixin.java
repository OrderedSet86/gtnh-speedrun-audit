package com.gtnhspeedrun.audit.mixins.late.serverutils;

import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.gtnhspeedrun.audit.GtnhSpeedrunAudit;
import com.gtnhspeedrun.audit.core.JsonUtil;
import com.gtnhspeedrun.audit.core.SessionManager;
import com.gtnhspeedrun.audit.trackers.NbtEdit;

import serverutils.command.CmdEditNBT;
import serverutils.lib.data.ForgePlayer;
import serverutils.lib.data.Universe;
import serverutils.net.MessageEditNBTResponse;

/**
 * Logs each /nbtedit write. ServerUtilities applies the edit of /nbtedit through this packet, not through a second
 * command. Without this hook, the command log has the opening of the editor and not the written NBT. The hook runs at
 * HEAD, so forged packets that fail the pending-edit check later in onMessage() are logged as attempts.
 *
 * <p>
 * At HEAD the target still has the current NBT, so the log line of a pending edit has the current NBT next to the new
 * NBT. The payload of the packet has only the new NBT. For a target that cannot be read, and for a packet with no
 * pending edit, the line has a reason in place of the current NBT.
 */
@Mixin(value = MessageEditNBTResponse.class, remap = false)
public class MessageEditNBTResponseMixin {

    /** An NBT whose Base64 is longer than this is logged as a hash only. A full player NBT can be hundreds of KB. */
    private static final int MAX_B64_CHARS = 65536;

    @Shadow(remap = false)
    private NBTTagCompound info;

    @Shadow(remap = false)
    private NBTTagCompound mainNbt;

    /** Logs the edit, with the hash and Base64 of the new NBT and of the target's current NBT. */
    @Inject(method = "onMessage", at = @At("HEAD"), require = 1)
    private void audit$logEdit(EntityPlayerMP player, CallbackInfo ci) { // noqa: NAR130
        if (!(info != null && mainNbt != null)) {
            return;
        }
        final String type = info.getString("type");
        final String description = switch (type) {
            case "block" -> "block " + info.getInteger("x") + "," + info.getInteger("y") + "," + info.getInteger("z");
            case "player" -> "player " + info.getString("id");
            case "entity" -> "entity " + info.getInteger("id");
            default -> type + " " + info.getString("id");
        };
        // onMessage() writes the payload only when the info equals the player's entry in CmdEditNBT.EDITING, and
        // throws an NPE for a player with no entry. Any other packet changes nothing. Such a packet is logged with no
        // current NBT, and past the limit the packet is counted, not logged
        final boolean pending = info.equals(
            CmdEditNBT.EDITING.get(
                player.getGameProfile()
                    .getId()));
        if (!(pending || audit$tryLogAttempt(player))) {
            return;
        }
        final NbtEdit edit = NbtEdit.of(type, description);
        if (pending) {
            audit$recordBefore(edit, player, type);
        } else {
            edit.beforeMissing("no pending edit");
        }
        final String b64 = JsonUtil.nbtToB64(mainNbt);
        edit.player(player)
            .written(JsonUtil.canonicalNbtHash(mainNbt), b64.length() <= MAX_B64_CHARS ? b64 : null)
            .log();
    }

    /** Record the target's current NBT on {@code edit}, or the reason why there is none. */
    private void audit$recordBefore(NbtEdit edit, EntityPlayerMP player, String type) { // noqa: NAR130
        Object before;
        try {
            before = audit$readBefore(player, type);
        } catch (RuntimeException | LinkageError failure) { // noqa: NAR142, degrade over another mod's writeToNBT()
            // the edit must go through even when the read of the current NBT fails
            before = "error: " + failure;
        }
        if (before instanceof NBTTagCompound beforeNbt) {
            final String encoded = JsonUtil.nbtToB64(beforeNbt);
            edit.before(JsonUtil.canonicalNbtHash(beforeNbt), encoded.length() <= MAX_B64_CHARS ? encoded : null);
        } else {
            edit.beforeMissing((String) before);
        }
    }

    /** Return whether the session logs this attempt. False without a session, or past the limit. */
    private static boolean audit$tryLogAttempt(EntityPlayerMP player) { // noqa: NAR130
        final SessionManager session = GtnhSpeedrunAudit.session();
        return session != null && session.attempts()
            .tryLog(
                player.getGameProfile()
                    .getId()
                    .toString(),
                player.getCommandSenderName(),
                "nbt_edit");
    }

    /**
     * Reads the target's current NBT, or returns the reason why there is none. The lookup is the lookup of onMessage(),
     * so no chunk is loaded.
     */
    private Object audit$readBefore(EntityPlayerMP player, String type) { // noqa: NAR130
        switch (type) {
            case "block" -> {
                final int x = info.getInteger("x");
                final int y = info.getInteger("y");
                final int z = info.getInteger("z");
                if (!player.worldObj.getChunkProvider()
                    .chunkExists(x >> 4, z >> 4)) {
                    return "chunk not loaded";
                }
                final TileEntity tile = player.worldObj.getTileEntity(x, y, z);
                if (tile == null) {
                    return "no tile entity";
                }
                final NBTTagCompound nbt = new NBTTagCompound();
                tile.writeToNBT(nbt);
                return nbt;
            }
            case "entity" -> {
                final Entity entity = player.worldObj.getEntityByID(info.getInteger("id"));
                if (entity == null) {
                    return "no entity";
                }
                final NBTTagCompound nbt = new NBTTagCompound();
                entity.writeToNBT(nbt);
                return nbt;
            }
            case "player" -> {
                final ForgePlayer target = Universe.get()
                    .getPlayer(info.getString("id"));
                return target == null ? "no player" : target.getPlayerNBT();
            }
            case "item" -> {
                final ItemStack held = player.getHeldItem();
                return held == null ? "empty hand" : held.writeToNBT(new NBTTagCompound());
            }
            default -> {
                return "unknown target type";
            }
        }
    }
}
