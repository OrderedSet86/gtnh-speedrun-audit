package com.gtnhspeedrun.audit.mixins.late.gt;

import net.minecraft.tileentity.TileEntity;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.gtnhspeedrun.audit.trackers.BlockSpot;
import com.gtnhspeedrun.audit.trackers.MachineRemovals;
import com.gtnhspeedrun.audit.trackers.RemovalCause;

import gregtech.api.metatileentity.BaseTileEntity;

/**
 * Notes {@code burned} as the cause of the removal that setToFire() makes. PowerNodePath calls setToFire() for a cable
 * over the cable's amperage limit, and MTEFluidPipe calls setToFire() for a pipe with a fluid too hot for the pipe.
 */
@Mixin(value = BaseTileEntity.class, remap = false)
public class BaseTileEntityMixin {

    @Inject(method = "setToFire", at = @At("HEAD"), require = 1)
    private void audit$burned(CallbackInfo ci) { // noqa: NAR130
        final TileEntity self = (TileEntity) (Object) this;
        if (!(self.getWorldObj() != null && !self.getWorldObj().isRemote)) {
            return;
        }
        MachineRemovals.noteRemovalCause(
            new BlockSpot(self.getWorldObj().provider.dimensionId, self.xCoord, self.yCoord, self.zCoord),
            RemovalCause.Cause.BURNED);
    }
}
