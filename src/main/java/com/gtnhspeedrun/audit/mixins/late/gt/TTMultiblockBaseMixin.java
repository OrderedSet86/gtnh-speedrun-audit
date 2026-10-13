package com.gtnhspeedrun.audit.mixins.late.gt;

import java.util.ArrayList;
import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.gtnhspeedrun.audit.trackers.GtExtraHatches;
import com.gtnhspeedrun.audit.trackers.GtFormationLogger;

import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.metatileentity.implementations.MTEHatch;
import gregtech.api.metatileentity.implementations.MTEMultiBlockBase;
import tectech.thing.metaTileEntity.hatch.MTEHatchDynamoMulti;
import tectech.thing.metaTileEntity.hatch.MTEHatchEnergyMulti;
import tectech.thing.metaTileEntity.multi.base.TTMultiblockBase;

/**
 * TecTech multiblocks (Large Naquadah Reactor, Eye of Harmony, Space Elevator and its modules, Quantum Computer,
 * BEC, Godforge modules, …) set mMachine inside their own onPostTick and never call checkStructure, so the
 * MTEMultiBlockBase hook only saw them when something else ran checkStructure, such as a survival build. The
 * formation is read at hatchInit_EM: it runs once per structure check, after the final mMachine (including the
 * uncertainty-hatch veto) and after the hatches are set up. Pack 2.8.4 calls checkMachine there instead of
 * checkMachine_TT, so neither is a target. TecTech saves mMachine (NBT "eOK"), so a reload starts formed and
 * logs nothing; the controller's formation marker dedupes a second check in the same tick. TecTech keeps its
 * multi-amp and laser hatches in its own lists; they reach the formation's power scan through GtExtraHatches.
 */
@Mixin(value = TTMultiblockBase.class, remap = false)
public class TTMultiblockBaseMixin implements GtExtraHatches {

    @Shadow
    protected ArrayList<MTEHatchEnergyMulti> eEnergyMulti;

    @Shadow
    protected ArrayList<MTEHatchDynamoMulti> eDynamoMulti;

    @Unique
    private boolean audit$ttWasFormed;

    @Inject(method = "onPostTick", at = @At("HEAD"), require = 1)
    private void audit$before(IGregTechTileEntity aBaseMetaTileEntity, long aTick, CallbackInfo ci) {
        audit$ttWasFormed = ((MTEMultiBlockBase) (Object) this).mMachine;
    }

    @Inject(
        method = "onPostTick",
        at = @At(
            value = "INVOKE",
            target = "Ltectech/thing/metaTileEntity/multi/base/TTMultiblockBase;hatchInit_EM(Z)V"),
        require = 1)
    private void audit$checked(IGregTechTileEntity aBaseMetaTileEntity, long aTick, CallbackInfo ci) {
        if (audit$ttWasFormed || !((MTEMultiBlockBase) (Object) this).mMachine || !aBaseMetaTileEntity.isServerSide()) {
            return;
        }
        audit$ttWasFormed = true;
        ((GtFormationLogger) this).audit$logFormation(aBaseMetaTileEntity);
    }

    @Override
    public List<? extends MTEHatch> audit$extraEnergyHatches() {
        return eEnergyMulti;
    }

    @Override
    public List<? extends MTEHatch> audit$extraDynamoHatches() {
        return eDynamoMulti;
    }
}
