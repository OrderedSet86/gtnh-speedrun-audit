package com.gtnhspeedrun.audit.mixins.late.gt;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.gtnhspeedrun.audit.trackers.AuditSinks;
import com.gtnhspeedrun.audit.trackers.GtMachineSites;

import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.metatileentity.MetaTileEntity;
import gregtech.common.tileentities.machines.multi.MTEBrickedBlastFurnace;

/**
 * Logs the formation of the Bricked Blast Furnace (the bbf% category). MTEBrickedBlastFurnace predates
 * MTEMultiBlockBase. MTEBrickedBlastFurnace extends MetaTileEntity directly, and mMachine is set inside
 * MTEBrickedBlastFurnace.onPostTick(), so the checkStructure() hook of MTEMultiBlockBaseMixin never runs for the
 * furnace.
 */
@Mixin(value = MTEBrickedBlastFurnace.class, remap = false)
public class MTEBrickedBlastFurnaceMixin {

    @Shadow
    public boolean mMachine;

    @Unique
    private boolean audit$wasFormed; // noqa: NAR130

    @Inject(method = "onPostTick", at = @At("HEAD"), require = 1)
    private void audit$before(IGregTechTileEntity aBaseMetaTileEntity, long aTimer, CallbackInfo ci) { // noqa: NAR130
        audit$wasFormed = mMachine;
    }

    @Inject(method = "onPostTick", at = @At("RETURN"), require = 1)
    private void audit$after(IGregTechTileEntity aBaseMetaTileEntity, long aTimer, CallbackInfo ci) { // noqa: NAR130
        if (!(!audit$wasFormed && mMachine && aBaseMetaTileEntity.isServerSide())) {
            return;
        }
        final MetaTileEntity self = (MetaTileEntity) (Object) this;
        AuditSinks.logMultiblockFormed("gt", GtMachineSites.readSite(self, aBaseMetaTileEntity));
    }
}
