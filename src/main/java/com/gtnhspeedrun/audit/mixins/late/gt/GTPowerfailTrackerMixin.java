package com.gtnhspeedrun.audit.mixins.late.gt;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.gtnhspeedrun.audit.trackers.GtMachineSites;
import com.gtnhspeedrun.audit.trackers.MachineEvents;

import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.common.data.GTPowerfailTracker;

/**
 * Logs every multiblock power failure. GT calls createPowerfailEvent() from MTEMultiBlockBase.stopMachine() when a
 * multiblock shuts down for POWER_LOSS. GT's record of power failures is the data behind /powerfails. That record is
 * per-team world data with only a count and the latest time for each machine, and players can wipe the record with
 * /powerfails clear. The audit log gets a line for each failure. createPowerfailEvent() has the same signature from GT
 * 5.09.51.482 (pack 2.8.4) through current.
 */
@Mixin(value = GTPowerfailTracker.class, remap = false)
public class GTPowerfailTrackerMixin {

    @Inject(method = "createPowerfailEvent", at = @At("HEAD"), require = 1)
    private void audit$powerfail(IGregTechTileEntity baseMetaTileEntity, CallbackInfo ci) { // noqa: NAR130
        if (!(baseMetaTileEntity != null && baseMetaTileEntity.getWorld() != null
            && !baseMetaTileEntity.getWorld().isRemote)) {
            return;
        }
        MachineEvents.logGtPowerfail(GtMachineSites.readSite(baseMetaTileEntity));
    }
}
