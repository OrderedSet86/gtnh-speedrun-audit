package com.gtnhspeedrun.audit.mixins.nei;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.play.INetHandlerPlayServer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.gtnhspeedrun.audit.trackers.NeiSinks;

import codechicken.lib.packet.PacketCustom;
import codechicken.nei.NEISPH;

/**
 * Logs every NEI cheat packet. Every NEI cheat goes through NEISPH.handlePacket(). {@link NEIServerUtilsMixin} logs
 * the decoded detail.
 */
@Mixin(value = NEISPH.class, remap = false)
public class NEISPHMixin {

    /**
     * Runs at HEAD, before {@code NEIServerConfig.authenticatePacket}, so a denied attempt is logged too. The packet
     * is a live stream. Do NOT read from the packet here.
     */
    @Inject(method = "handlePacket", at = @At("HEAD"), require = 1)
    private void audit$logPacket(PacketCustom packet, EntityPlayerMP sender, // noqa: NAR130, NAR131
        INetHandlerPlayServer netHandler, CallbackInfo ci) {
        NeiSinks.logNeiPacket(
            sender.getGameProfile()
                .getId()
                .toString(),
            sender.getCommandSenderName(),
            packet.getType());
    }
}
