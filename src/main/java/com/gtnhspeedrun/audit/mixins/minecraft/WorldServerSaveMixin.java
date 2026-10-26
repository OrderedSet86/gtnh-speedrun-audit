package com.gtnhspeedrun.audit.mixins.minecraft;

import net.minecraft.util.IProgressUpdate;
import net.minecraft.world.WorldServer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.gtnhspeedrun.audit.GtnhSpeedrunAudit;
import com.gtnhspeedrun.audit.core.SessionManager;

/**
 * Publishes the anchor at the start of each overworld save. saveAllChunks() writes the map storage, the anchor
 * included, before the chunks. The overworld is the only world whose saveLevel() writes the map storage.
 */
@Mixin(WorldServer.class)
public class WorldServerSaveMixin {

    @Inject(method = "saveAllChunks", at = @At("HEAD"), require = 1)
    private void audit$syncAnchor(boolean saveAll, IProgressUpdate progress, CallbackInfo ci) { // noqa: NAR130
        if (((WorldServer) (Object) this).provider.dimensionId != 0) {
            return;
        }
        final SessionManager session = GtnhSpeedrunAudit.session();
        if (session != null) {
            session.syncAnchorBeforeSave();
        }
    }
}
