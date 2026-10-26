package com.gtnhspeedrun.audit.mixins.late.gt;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.gtnhspeedrun.audit.trackers.BlockSpot;
import com.gtnhspeedrun.audit.trackers.MachineRemovals;
import com.gtnhspeedrun.audit.trackers.RemovalCause;

import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.metatileentity.MetaPipeEntity;

/** Notes {@code exploded} as the cause of the removal when a GT pipe or cable explodes. */
@Mixin(value = MetaPipeEntity.class, remap = false)
public class MetaPipeEntityMixin {

    @Inject(method = "doExplosion", at = @At("HEAD"), require = 1)
    private void audit$explosion(long aExplosionPower, CallbackInfo ci) { // noqa: NAR130
        final IGregTechTileEntity base = ((MetaPipeEntity) (Object) this).getBaseMetaTileEntity();
        if (!(base != null && base.getWorld() != null && !base.getWorld().isRemote)) {
            return;
        }
        MachineRemovals.noteRemovalCause(
            new BlockSpot(base.getWorld().provider.dimensionId, base.getXCoord(), base.getYCoord(), base.getZCoord()),
            RemovalCause.Cause.EXPLODED);
    }
}
