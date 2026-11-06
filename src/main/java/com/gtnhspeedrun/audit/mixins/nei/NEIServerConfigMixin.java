package com.gtnhspeedrun.audit.mixins.nei;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.google.gson.JsonObject;
import com.gtnhspeedrun.audit.trackers.NeiSinks;

import codechicken.nei.NEIServerConfig;

/**
 * Logs NEI's per-dimension action locks, set by the {@code CHANGE_PROPERTY} packet. With "rain" disabled, NEI stops
 * rain in the dimension. With a time zone disabled ("dawn", "noon", "dusk" or "midnight"), NEI's time change skips that
 * zone.
 */
@Mixin(value = NEIServerConfig.class, remap = false)
public class NEIServerConfigMixin {

    /** Logs the lock of the action {@code name} in dimension {@code dim}, set or cleared. */
    @Inject(method = "disableAction", at = @At("HEAD"), require = 1)
    private static void audit$disableAction(int dim, String name, boolean disable, // noqa: NAR130, NAR131
        CallbackInfo ci) {
        final JsonObject extra = new JsonObject();
        extra.addProperty("dim", dim);
        extra.addProperty("disable", disable);
        NeiSinks.NeiCheat.of("disable_action")
            .detail(name)
            .extra(extra)
            .log();
    }
}
