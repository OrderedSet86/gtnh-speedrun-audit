package com.gtnhspeedrun.audit.mixins.fml;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.gtnhspeedrun.audit.trackers.AuditSinks;

import cpw.mods.fml.common.network.handshake.FMLHandshakeMessage;
import cpw.mods.fml.common.network.handshake.NetworkDispatcher;
import io.netty.channel.ChannelHandlerContext;

/**
 * Passes the mod list that a client sends in the FML handshake to AuditSinks, so PlayerSessionTracker can log the list
 * at the player's join. The server gets a client's mod list only in the HELLO handshake state, the anonymous class
 * FMLHandshakeServerState$2 (the constants are START, HELLO, ...). FML logs the list to the console and then discards
 * the list. Verifiers want to see client mods such as Schematica or a WorldEdit client on a joining player.
 *
 * <p>
 * FML for 1.7.10 gets no more releases, so the anonymous class name is stable. The mixin is in the base (early)
 * config, because FML loads the FML classes long before the late phase starts.
 */
@Mixin(targets = "cpw.mods.fml.common.network.handshake.FMLHandshakeServerState$2", remap = false)
public class FMLHandshakeServerStateMixin {

    @Inject(method = "accept", at = @At("HEAD"), require = 1)
    private void audit$captureModList(ChannelHandlerContext context, FMLHandshakeMessage message, // noqa: NAR130
        CallbackInfoReturnable<Object> cir) {
        if (message instanceof FMLHandshakeMessage.ModList modList) {
            AuditSinks.storeClientModList(
                context.channel()
                    .attr(NetworkDispatcher.FML_DISPATCHER)
                    .get(),
                modList.modList());
        }
    }
}
