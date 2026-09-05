package com.gtnhspeedrun.audit.mixins.late.gt;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.gtnhspeedrun.audit.trackers.AuditSinks;

import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.metatileentity.MetaTileEntity;
import gregtech.common.tileentities.machines.multi.MTEBrickedBlastFurnace;

/**
 * The Bricked Blast Furnace (bbf%) predates MTEMultiBlockBase — it extends MetaTileEntity directly and flips
 * its own mMachine inside onPostTick, so it needs its own transition watch.
 */
@Mixin(value = MTEBrickedBlastFurnace.class, remap = false)
public class MTEBrickedBlastFurnaceMixin {

    @Shadow
    public boolean mMachine;

    @Unique
    private boolean audit$wasFormed;

    @Inject(method = "onPostTick", at = @At("HEAD"), require = 1)
    private void audit$before(IGregTechTileEntity aBaseMetaTileEntity, long aTimer, CallbackInfo ci) {
        audit$wasFormed = mMachine;
    }

    @Inject(method = "onPostTick", at = @At("RETURN"), require = 1)
    private void audit$after(IGregTechTileEntity aBaseMetaTileEntity, long aTimer, CallbackInfo ci) {
        if (audit$wasFormed || !mMachine || !aBaseMetaTileEntity.isServerSide()) {
            return;
        }
        final MetaTileEntity self = (MetaTileEntity) (Object) this;
        AuditSinks.multiblockFormed(
            "gt",
            self.getClass()
                .getSimpleName(),
            self.getLocalNameKey(),
            aBaseMetaTileEntity.getWorld().provider.dimensionId,
            aBaseMetaTileEntity.getXCoord(),
            aBaseMetaTileEntity.getYCoord(),
            aBaseMetaTileEntity.getZCoord());
    }
}
