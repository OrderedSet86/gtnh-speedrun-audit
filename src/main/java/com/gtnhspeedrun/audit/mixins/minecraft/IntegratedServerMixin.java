package com.gtnhspeedrun.audit.mixins.minecraft;

import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.world.WorldSettings;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.gtnhspeedrun.audit.trackers.CommandsAllowedTracker;

/**
 * Report a singleplayer world opened to LAN, from the Open to LAN screen or {@code /publish}. IntegratedServer exists
 * on the client only, so this mixin is in the client list of the base config.
 */
@Mixin(IntegratedServer.class)
public class IntegratedServerMixin {

    /** The return value is the LAN port, or null when the LAN endpoint failed to start. */
    @Inject(method = "shareToLAN", at = @At("RETURN"), require = 1)
    private void audit$sharedToLan(WorldSettings.GameType type, boolean allowCheats, // noqa: NAR130
        CallbackInfoReturnable<String> cir) {
        CommandsAllowedTracker.reportLanOpen(type.getName(), allowCheats, cir.getReturnValue());
    }
}
