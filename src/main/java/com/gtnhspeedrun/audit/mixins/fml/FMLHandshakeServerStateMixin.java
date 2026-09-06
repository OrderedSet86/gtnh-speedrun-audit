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
 * The one moment the server ever sees a client's mod list: the HELLO handshake state ($2 — constants are
 * START, HELLO, …) receives it, logs it to console and throws it away. Parked in AuditSinks instead, so
 * PlayerLoggedInEvent can attach it to the join record — Schematica or a WorldEdit client on a joining
 * player is exactly the kind of thing verifiers want visible. FML for 1.7.10 is frozen, so the anonymous
 * class name is stable. Rides the base (early) mixin config: FML's own classes are loaded long before the
 * late phase prepares.
 */
@Mixin(targets = "cpw.mods.fml.common.network.handshake.FMLHandshakeServerState$2", remap = false)
public class FMLHandshakeServerStateMixin {

    @Inject(method = "accept", at = @At("HEAD"), require = 1)
    private void audit$captureModList(ChannelHandlerContext ctx, FMLHandshakeMessage msg,
        CallbackInfoReturnable<Object> cir) {
        if (msg instanceof FMLHandshakeMessage.ModList modList) {
            AuditSinks.clientModList(
                ctx.channel()
                    .attr(NetworkDispatcher.FML_DISPATCHER)
                    .get(),
                modList.modList());
        }
    }
}
