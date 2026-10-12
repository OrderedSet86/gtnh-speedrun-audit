package com.gtnhspeedrun.audit.mixins.late.gt;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.gtnhspeedrun.audit.trackers.AuditSinks;

import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.common.data.GTPowerfailTracker;

/**
 * The data behind /powerfails: GT calls this from MTEMultiBlockBase.stopMachine when a multiblock shuts down for
 * POWER_LOSS. GT's own record is per-team world data that players can wipe with /powerfails clear and that
 * keeps only a count and the latest time per machine; this keeps every occurrence. Present with the same
 * signature from GT 5.09.51.482 (pack 2.8.4) through current.
 */
@Mixin(value = GTPowerfailTracker.class, remap = false)
public class GTPowerfailTrackerMixin {

    @Inject(method = "createPowerfailEvent", at = @At("HEAD"), require = 1)
    private void audit$powerfail(IGregTechTileEntity igte, CallbackInfo ci) {
        if (igte == null || igte.getWorld() == null || igte.getWorld().isRemote) {
            return;
        }
        final IMetaTileEntity mte = igte.getMetaTileEntity();
        AuditSinks.gtPowerfail(
            mte == null ? "?"
                : mte.getClass()
                    .getSimpleName(),
            mte == null ? null : AuditSinks.gtLocalName(mte),
            igte.getWorld().provider.dimensionId,
            igte.getXCoord(),
            igte.getYCoord(),
            igte.getZCoord());
    }
}
