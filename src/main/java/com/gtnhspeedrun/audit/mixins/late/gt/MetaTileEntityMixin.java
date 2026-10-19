package com.gtnhspeedrun.audit.mixins.late.gt;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.gtnhspeedrun.audit.trackers.AuditSinks;
import com.gtnhspeedrun.audit.trackers.GtMachineSites;

import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.metatileentity.MetaTileEntity;

/**
 * Logs GT machine explosions. An explosion is the usual reason for a backup restore, so verifiers see the cause
 * next to the WORLD_ROLLBACK verdict.
 */
@Mixin(value = MetaTileEntity.class, remap = false)
public class MetaTileEntityMixin {

    @Inject(method = "doExplosion", at = @At("HEAD"), require = 1)
    private void audit$explosion(long aExplosionPower, CallbackInfo ci) { // noqa: NAR130
        final MetaTileEntity self = (MetaTileEntity) (Object) this;
        final IGregTechTileEntity base = self.getBaseMetaTileEntity();
        if (!(base != null && base.getWorld() != null && !base.getWorld().isRemote)) {
            return;
        }
        AuditSinks.logGtExplosion(GtMachineSites.readSite(self, base), aExplosionPower);
    }
}
