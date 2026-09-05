package com.gtnhspeedrun.audit.mixins.late.gt;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.gtnhspeedrun.audit.trackers.AuditSinks;

import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.metatileentity.MetaTileEntity;
import gregtech.api.metatileentity.implementations.MTEMultiBlockBase;

/**
 * The formation timestamp for every modern GT multiblock (assembly line, EBF, fusion, …): checkStructure is
 * the single place mMachine flips. The false→true transition is the graded event for the coke%/bbf%/assline%
 * style categories.
 */
@Mixin(value = MTEMultiBlockBase.class, remap = false)
public class MTEMultiBlockBaseMixin {

    @Shadow
    public boolean mMachine;

    @Unique
    private boolean audit$wasFormed;

    @Inject(
        method = "checkStructure(ZLgregtech/api/interfaces/tileentity/IGregTechTileEntity;)Z",
        at = @At("HEAD"),
        require = 1)
    private void audit$before(boolean aForceReset, IGregTechTileEntity aBaseMetaTileEntity,
        CallbackInfoReturnable<Boolean> cir) {
        audit$wasFormed = mMachine;
    }

    @Inject(
        method = "checkStructure(ZLgregtech/api/interfaces/tileentity/IGregTechTileEntity;)Z",
        at = @At("RETURN"),
        require = 1)
    private void audit$after(boolean aForceReset, IGregTechTileEntity aBaseMetaTileEntity,
        CallbackInfoReturnable<Boolean> cir) {
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
