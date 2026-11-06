package com.gtnhspeedrun.audit.mixins.late.gt;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.gtnhspeedrun.audit.trackers.PowerTally;
import com.gtnhspeedrun.audit.trackers.PowerUse;
import com.gtnhspeedrun.audit.trackers.PowerUseCounter;

import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.metatileentity.BaseMetaTileEntity;
import gregtech.api.metatileentity.MetaTileEntity;

/**
 * Feeds every GT machine tick and every EU drain to {@link PowerUse}. A consumer drains EU through
 * decreaseStoredEnergyUnits(), from the consumer's tile or from a hatch. Emission to cables, item charging and IC2
 * drawEnergy() call decreaseStoredEU() directly, so no transfer to a cable or an item reaches this hook. The drain is
 * the fall in stored EU over the call: steam machines drain steam through the same method, and a drain below zero
 * stops at zero.
 * updateEntityProfiled() and decreaseStoredEnergyUnits() have the same signatures from GT 5.09.51.482 (pack 2.8.4)
 * through current.
 */
@Mixin(value = BaseMetaTileEntity.class, remap = false)
public class BaseMetaTileEntityPowerMixin implements PowerUseCounter {

    @Unique
    private PowerTally audit$powerTally; // noqa: NAR130

    @Inject(method = "updateEntityProfiled", at = @At("HEAD"), require = 1)
    private void audit$enterTick(CallbackInfo ci) { // noqa: NAR130
        PowerUse.active()
            .enterTile(this, audit$readStoredEu());
    }

    @Inject(method = "updateEntityProfiled", at = @At("RETURN"), require = 1)
    private void audit$exitTick(CallbackInfo ci) { // noqa: NAR130
        PowerUse.active()
            .exitTile(audit$readStoredEu());
    }

    @Inject(method = "decreaseStoredEnergyUnits", at = @At("HEAD"), require = 1)
    private void audit$beforeDrain(long aEnergy, boolean aIgnoreTooLessEnergy, // noqa: NAR130
        CallbackInfoReturnable<Boolean> cir) {
        PowerUse.active()
            .openDrain(audit$readStoredEu());
    }

    @Inject(method = "decreaseStoredEnergyUnits", at = @At("RETURN"), require = 1)
    private void audit$afterDrain(long aEnergy, boolean aIgnoreTooLessEnergy, // noqa: NAR130
        CallbackInfoReturnable<Boolean> cir) {
        PowerUse.active()
            .closeDrain(this, audit$readStoredEu());
    }

    @Override
    public PowerTally audit$readPowerTally() {
        return audit$powerTally;
    }

    @Override
    public void audit$writePowerTally(PowerTally tally) {
        audit$powerTally = tally;
    }

    /**
     * Read the EU field that a drain lowers. getStoredEU() caps at the capacity, and a drain from an overfull buffer
     * would not show.
     */
    @Unique
    private long audit$readStoredEu() { // noqa: NAR130
        return ((IGregTechTileEntity) (Object) this).getMetaTileEntity() instanceof MetaTileEntity machine
            ? machine.getEUVar()
            : 0;
    }
}
