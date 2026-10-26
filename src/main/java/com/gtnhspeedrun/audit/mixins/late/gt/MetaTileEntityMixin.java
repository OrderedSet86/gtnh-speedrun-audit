package com.gtnhspeedrun.audit.mixins.late.gt;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.gtnhspeedrun.audit.trackers.BlockSpot;
import com.gtnhspeedrun.audit.trackers.GtMachineSites;
import com.gtnhspeedrun.audit.trackers.MachineEvents;
import com.gtnhspeedrun.audit.trackers.MachineRemovals;
import com.gtnhspeedrun.audit.trackers.RemovalCause;

import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.metatileentity.MetaTileEntity;

/**
 * Logs GT machine explosions. An explosion is the usual reason for a backup restore, so verifiers see the cause next
 * to the WORLD_ROLLBACK verdict. The cause of the machine's removal is noted as {@code exploded}.
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
        MachineEvents.logGtExplosion(GtMachineSites.readSite(self, base), aExplosionPower);
        MachineRemovals.noteRemovalCause(
            new BlockSpot(base.getWorld().provider.dimensionId, base.getXCoord(), base.getYCoord(), base.getZCoord()),
            RemovalCause.Cause.EXPLODED);
    }
}
