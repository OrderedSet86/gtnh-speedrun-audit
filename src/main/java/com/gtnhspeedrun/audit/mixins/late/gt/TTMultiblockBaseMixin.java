package com.gtnhspeedrun.audit.mixins.late.gt;

import java.util.ArrayList;
import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.gtnhspeedrun.audit.trackers.FormationGuard;
import com.gtnhspeedrun.audit.trackers.GtExtraHatches;
import com.gtnhspeedrun.audit.trackers.GtFormationLogger;
import com.gtnhspeedrun.audit.trackers.GtMachineSites;
import com.gtnhspeedrun.audit.trackers.StructureBounds;

import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.metatileentity.MetaTileEntity;
import gregtech.api.metatileentity.implementations.MTEHatch;
import gregtech.api.metatileentity.implementations.MTEMultiBlockBase;
import tectech.thing.metaTileEntity.hatch.MTEHatchDynamoMulti;
import tectech.thing.metaTileEntity.hatch.MTEHatchEnergyMulti;
import tectech.thing.metaTileEntity.multi.base.TTMultiblockBase;

/**
 * Logs the formation of TecTech multiblocks (Large Naquadah Reactor, Eye of Harmony, Space Elevator and its modules,
 * Quantum Computer, BEC, Godforge modules, ...). TTMultiblockBase sets mMachine inside its onPostTick() and never
 * calls checkStructure(). The checkStructure() hook of MTEMultiBlockBaseMixin therefore runs for a TecTech multiblock
 * only when something else calls checkStructure(), for example a survival build.
 *
 * <p>
 * The formation is read at the call to hatchInit_EM(). That call happens once per structure check, after the final
 * mMachine value (including the veto of the uncertainty hatch) and after the hatches are set up. The TecTech of pack
 * 2.8.4 calls checkMachine() at that point, and later TecTech versions call checkMachine_TT(), so neither method is a
 * target. TecTech saves mMachine (NBT "eOK"), so after a reload the controller starts formed, and no formation is
 * logged. audit$ttWasFormed is set at the first formation, so no second formation is logged for a second check in the
 * same tick.
 *
 * <p>
 * TecTech's multi-amp and laser hatches are in separate lists, eEnergyMulti and eDynamoMulti. This mixin returns
 * the two lists through GtExtraHatches for the power scan of the formation. The onPostTick() hook opens a
 * StructureBounds frame for the tick, so the footprint of the structure check is available at hatchInit_EM().
 */
@Mixin(value = TTMultiblockBase.class, remap = false)
public class TTMultiblockBaseMixin implements GtExtraHatches {

    @Shadow
    protected ArrayList<MTEHatchEnergyMulti> eEnergyMulti; // noqa: NAR140

    @Shadow
    protected ArrayList<MTEHatchDynamoMulti> eDynamoMulti; // noqa: NAR140

    @Unique
    private boolean audit$ttWasFormed; // noqa: NAR130

    @Inject(method = "onPostTick", at = @At("HEAD"), require = 1)
    private void audit$before(IGregTechTileEntity aBaseMetaTileEntity, long aTick, CallbackInfo ci) { // noqa: NAR130
        audit$ttWasFormed = ((MTEMultiBlockBase) (Object) this).mMachine;
        StructureBounds.push(this, aBaseMetaTileEntity.isServerSide());
    }

    @Inject(
        method = "onPostTick",
        at = @At(
            value = "INVOKE",
            target = "Ltectech/thing/metaTileEntity/multi/base/TTMultiblockBase;hatchInit_EM(Z)V"),
        require = 1)
    private void audit$checked(IGregTechTileEntity aBaseMetaTileEntity, long aTick, CallbackInfo ci) { // noqa: NAR130
        if (!(((MTEMultiBlockBase) (Object) this).mMachine && aBaseMetaTileEntity.isServerSide())) {
            return;
        }
        final boolean wasFormed = audit$ttWasFormed;
        audit$ttWasFormed = true;
        FormationGuard.log(
            (GtFormationLogger) this,
            wasFormed,
            GtMachineSites.readSite((MetaTileEntity) (Object) this, aBaseMetaTileEntity));
    }

    @Inject(method = "onPostTick", at = @At("RETURN"), require = 1)
    private void audit$after(IGregTechTileEntity aBaseMetaTileEntity, long aTick, CallbackInfo ci) { // noqa: NAR130
        StructureBounds.pop(this);
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
