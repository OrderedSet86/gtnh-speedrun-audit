package com.gtnhspeedrun.audit.mixins.late.gt;

import net.minecraft.entity.player.EntityPlayerMP;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.google.gson.JsonObject;
import com.gtnhspeedrun.audit.trackers.ProspectSinks;

import detrav.net.DetravNetwork;
import detrav.net.DetravPacket;
import detrav.net.ProspectingPacket;

/**
 * Reports each map of the Electric Prospector's Scanner. On GT 5.09.52 and later, the scan finishes ticks after the
 * right-click, on a GT scheduler. A cancelled scan has no map packet. The send is the one point that runs once per
 * finished scan in GT 5.09.51 and 5.09.54. The packet's layout of ore data differs between those versions, so only the
 * packet's final int fields are read.
 */
@Mixin(value = DetravNetwork.class, remap = false)
public class DetravNetworkMixin {

    @Inject(method = "sendToPlayer", at = @At("HEAD"), require = 1)
    private void audit$sendToPlayer(DetravPacket packet, EntityPlayerMP player, CallbackInfo ci) { // noqa: NAR130
        if (!(packet instanceof ProspectingPacket scan)) {
            return;
        }
        final JsonObject fields = new JsonObject();
        fields.addProperty("x", scan.posX);
        fields.addProperty("z", scan.posZ);
        fields.addProperty("chunkX", scan.chunkX);
        fields.addProperty("chunkZ", scan.chunkZ);
        fields.addProperty("radius", scan.size);
        fields.addProperty("mode", scan.ptype);
        ProspectSinks.reportScannerMap(player, fields);
    }
}
