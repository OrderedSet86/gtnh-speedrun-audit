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

import com.gtnhspeedrun.audit.core.JsonUtil;
import com.gtnhspeedrun.audit.trackers.AuditSinks;

import serverutils.lib.data.ForgePlayer;
import serverutils.lib.data.Universe;
import serverutils.net.MessageEditNBTResponse;

/**
 * ServerUtilities' /nbtedit applies its edit through this packet, not through a second command — without this
 * hook the command log shows the editor being OPENED but never what was written. HEAD: forged packets that
 * fail the pending-edit check downstream still get recorded as attempts.
 *
 * <p>
 * HEAD is also the last moment the target still holds its old NBT, so the line carries that too: the payload
 * alone says what was set, never what it replaced. The target is read the way CmdEditNBT reads it for the
 * editor, without loading a chunk; a target that cannot be read gets a reason instead of a payload.
 */
@Mixin(value = MessageEditNBTResponse.class, remap = false)
public class MessageEditNBTResponseMixin {

    /** Payloads above this stay hash-only — a full player NBT can be hundreds of KB. */
    private static final int MAX_B64_CHARS = 65536;

    @Shadow(remap = false)
    private NBTTagCompound info;

    @Shadow(remap = false)
    private NBTTagCompound mainNbt;

    @Inject(method = "onMessage", at = @At("HEAD"), require = 1)
    private void audit$logEdit(EntityPlayerMP player, CallbackInfo ci) {
        if (info == null || mainNbt == null) {
            return;
        }
        final String type = info.getString("type");
        final String desc = switch (type) {
            case "block" -> "block " + info.getInteger("x") + "," + info.getInteger("y") + "," + info.getInteger("z");
            case "player" -> "player " + info.getString("id");
            case "entity" -> "entity " + info.getInteger("id");
            default -> type + " " + info.getString("id");
        };
        Object before;
        try {
            before = audit$readBefore(player, type);
        } catch (Throwable t) {
            // the edit must go through whatever the audit fails to read
            before = "error: " + t;
        }
        String beforeHash = null;
        String beforeB64 = null;
        String beforeMissing = null;
        if (before instanceof NBTTagCompound beforeNbt) {
            beforeHash = JsonUtil.canonicalNbtHash(beforeNbt);
            final String encoded = JsonUtil.nbtToB64(beforeNbt);
            beforeB64 = encoded.length() <= MAX_B64_CHARS ? encoded : null;
        } else {
            beforeMissing = (String) before;
        }
        final String b64 = JsonUtil.nbtToB64(mainNbt);
        AuditSinks.nbtEdit(
            type,
            desc,
            player.getGameProfile()
                .getId()
                .toString(),
            player.getCommandSenderName(),
            JsonUtil.canonicalNbtHash(mainNbt),
            b64.length() <= MAX_B64_CHARS ? b64 : null,
            beforeHash,
            beforeB64,
            beforeMissing);
    }

    /** The target's current NBT, or why there is none. Mirrors the target lookup in onMessage. */
    private Object audit$readBefore(EntityPlayerMP player, String type) {
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
