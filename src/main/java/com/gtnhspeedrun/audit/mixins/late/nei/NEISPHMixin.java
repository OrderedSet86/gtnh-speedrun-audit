package com.gtnhspeedrun.audit.mixins.late.nei;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.play.INetHandlerPlayServer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.gtnhspeedrun.audit.trackers.AuditSinks;

import codechicken.lib.packet.PacketCustom;
import codechicken.nei.NEISPH;

/**
 * Every NEI cheat flows through here. HEAD lands before {@code NEIServerConfig.authenticatePacket}, so denied
 * attempts are recorded too. The packet is a live stream — do NOT read from it here; the decoded detail comes
 * from {@link NEIServerUtilsMixin}.
 */
@Mixin(value = NEISPH.class, remap = false)
public class NEISPHMixin {

    @Inject(method = "handlePacket", at = @At("HEAD"), require = 1)
    private void audit$logPacket(PacketCustom packet, EntityPlayerMP sender, INetHandlerPlayServer netHandler,
        CallbackInfo ci) {
        AuditSinks.neiPacket(
            sender.getGameProfile()
                .getId()
                .toString(),
            sender.getCommandSenderName(),
            packet.getType());
    }
}
