package com.gtnhspeedrun.audit.mixins.late.gt;

import net.minecraft.entity.player.EntityPlayer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.gtnhspeedrun.audit.trackers.ProspectSinks;

import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.common.tileentities.machines.basic.MTEAdvSeismicProspector;

/**
 * Marks a right-click on an Advanced Seismic Prospector as one prospecting use, at the machine's block. A right-click
 * with a data stick on an armed prospector writes the scan to the stick. {@link GTUtilityItemNbtMixin} has the hook for
 * that write.
 */
@Mixin(value = MTEAdvSeismicProspector.class, remap = false)
public class MTEAdvSeismicProspectorMixin {

    @Inject(method = "onRightclick", at = @At("HEAD"), require = 1)
    private void audit$beginUse(IGregTechTileEntity base, EntityPlayer player, // noqa: NAR130
        CallbackInfoReturnable<Boolean> cir) {
        if (base.isServerSide()) {
            ProspectSinks.beginToolUse(
                player,
                new ProspectSinks.Tool("adv_seismic_prospector", null),
                new ProspectSinks.Place(base.getWorld(), base.getXCoord(), base.getYCoord(), base.getZCoord()));
        }
    }

    @Inject(method = "onRightclick", at = @At("RETURN"), require = 1)
    private void audit$endUse(IGregTechTileEntity base, EntityPlayer player, // noqa: NAR130
        CallbackInfoReturnable<Boolean> cir) {
        if (base.isServerSide()) {
            ProspectSinks.endToolUse();
        }
    }
}
