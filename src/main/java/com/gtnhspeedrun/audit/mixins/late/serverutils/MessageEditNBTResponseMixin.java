package com.gtnhspeedrun.audit.mixins.late.serverutils;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.NBTTagCompound;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.gtnhspeedrun.audit.core.JsonUtil;
import com.gtnhspeedrun.audit.trackers.AuditSinks;

import serverutils.net.MessageEditNBTResponse;

/**
 * ServerUtilities' /nbtedit applies its edit through this packet, not through a second command — without this
 * hook the command log shows the editor being OPENED but never what was written. HEAD: forged packets that
 * fail the pending-edit check downstream still get recorded as attempts.
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
        final String b64 = JsonUtil.nbtToB64(mainNbt);
        AuditSinks.nbtEdit(
            type,
            desc,
            player.getGameProfile()
                .getId()
                .toString(),
            player.getCommandSenderName(),
            JsonUtil.canonicalNbtHash(mainNbt),
            b64.length() <= MAX_B64_CHARS ? b64 : null);
    }
}
