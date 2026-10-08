package com.gtnhspeedrun.audit.mixins.late.gt;

import java.util.ArrayList;
import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.gtnhspeedrun.audit.trackers.AuditSinks;
import com.gtnhspeedrun.audit.trackers.MultiblockPower;

import gregtech.api.enums.GTValues;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.metatileentity.MetaTileEntity;
import gregtech.api.metatileentity.implementations.MTEHatch;
import gregtech.api.metatileentity.implementations.MTEHatchDynamo;
import gregtech.api.metatileentity.implementations.MTEHatchEnergy;
import gregtech.api.metatileentity.implementations.MTEMultiBlockBase;
import gregtech.api.util.GTUtility;

/**
 * The formation timestamp for every modern GT multiblock (assembly line, EBF, fusion, …): checkStructure is
 * the single place mMachine flips. The false→true transition is the graded event for the coke%/bbf%/assline%
 * style categories. The hatch lists are filled by the structure check itself, so at RETURN they describe the
 * multiblock that just formed — that is where the voltage it can run at comes from.
 */
@Mixin(value = MTEMultiBlockBase.class, remap = false)
public class MTEMultiBlockBaseMixin {

    @Shadow
    public boolean mMachine;

    @Shadow
    public ArrayList<MTEHatchEnergy> mEnergyHatches;

    @Shadow
    public ArrayList<MTEHatchDynamo> mDynamoHatches;

    @Shadow
    protected List<MTEHatch> mExoticEnergyHatches;

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
            AuditSinks.gtLocalName(self),
            aBaseMetaTileEntity.getWorld().provider.dimensionId,
            aBaseMetaTileEntity.getXCoord(),
            aBaseMetaTileEntity.getYCoord(),
            aBaseMetaTileEntity.getZCoord(),
            audit$power());
    }

    /**
     * Each hatch's own voltage, not getMaxInputVoltage(): that one sums voltages across hatches, so four LV
     * hatches would read as one HV. Multiblocks that keep hatches in their own lists (TecTech's eEnergyMulti,
     * GT++'s mAllEnergyHatches) report only what also reached the base lists.
     */
    @Unique
    private MultiblockPower audit$power() {
        final int[] energy = audit$scan(mEnergyHatches, true, new int[] { 0, -1 });
        audit$scan(mExoticEnergyHatches, true, energy);
        final int[] dynamo = audit$scan(mDynamoHatches, false, new int[] { 0, -1 });
        return new MultiblockPower(energy[0], audit$tierName(energy[1]), dynamo[0], audit$tierName(dynamo[1]));
    }

    /** Adds each live hatch to {count, highest tier}; a hatch with no voltage counts but sets no tier. */
    @Unique
    private static int[] audit$scan(List<? extends MTEHatch> hatches, boolean input, int[] countAndTier) {
        if (hatches == null) {
            return countAndTier;
        }
        for (MTEHatch hatch : hatches) {
            final IGregTechTileEntity base = hatch == null ? null : hatch.getBaseMetaTileEntity();
            if (base == null) {
                continue;
            }
            countAndTier[0]++;
            final long voltage = input ? base.getInputVoltage() : base.getOutputVoltage();
            if (voltage > 0) {
                countAndTier[1] = Math.max(countAndTier[1], GTUtility.getTier(voltage));
            }
        }
        return countAndTier;
    }

    @Unique
    private static String audit$tierName(int tier) {
        return tier >= 0 && tier < GTValues.VN.length ? GTValues.VN[tier] : null;
    }
}
