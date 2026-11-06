package com.gtnhspeedrun.audit.mixins.late.gt;

import java.math.BigInteger;
import java.util.UUID;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.gtnhspeedrun.audit.trackers.PowerUse;

import gregtech.common.misc.WirelessNetworkManager;

/**
 * Feeds every wireless EU drain to {@link PowerUse}. A machine takes EU from the wireless network of the owner's team
 * with a negative addEUToGlobalEnergyMap(), and the long and int overloads call the BigInteger one. The method returns
 * false and moves no EU when the network has too little. The signature is the same from GT 5.09.51.482 (pack 2.8.4)
 * through current.
 */
@Mixin(value = WirelessNetworkManager.class, remap = false)
public class WirelessNetworkManagerMixin {

    @Inject(method = "addEUToGlobalEnergyMap(Ljava/util/UUID;Ljava/math/BigInteger;)Z", at = @At("RETURN"), require = 1)
    private static void audit$wirelessDrain(UUID user, BigInteger eu, // noqa: NAR130
        CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ() && eu.signum() < 0) {
            PowerUse.active()
                .noteWirelessDrain(eu.negate());
        }
    }
}
