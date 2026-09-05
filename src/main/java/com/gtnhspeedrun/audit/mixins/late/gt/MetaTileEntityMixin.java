package com.gtnhspeedrun.audit.mixins.late.gt;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.gtnhspeedrun.audit.trackers.AuditSinks;

import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.metatileentity.MetaTileEntity;

/**
 * Machine explosions are the usual reason a backup restore follows; logging them gives verifiers the "why"
 * next to the WORLD_ROLLBACK verdict.
 */
@Mixin(value = MetaTileEntity.class, remap = false)
public class MetaTileEntityMixin {

    @Inject(method = "doExplosion", at = @At("HEAD"), require = 1)
    private void audit$explosion(long aExplosionPower, CallbackInfo ci) {
        final MetaTileEntity self = (MetaTileEntity) (Object) this;
        final IGregTechTileEntity base = self.getBaseMetaTileEntity();
        if (base == null || base.getWorld() == null || base.getWorld().isRemote) {
            return;
        }
        AuditSinks.gtExplosion(
            self.getClass()
                .getSimpleName(),
            self.getLocalNameKey(),
            base.getWorld().provider.dimensionId,
            base.getXCoord(),
            base.getYCoord(),
            base.getZCoord(),
            aExplosionPower);
    }
}
