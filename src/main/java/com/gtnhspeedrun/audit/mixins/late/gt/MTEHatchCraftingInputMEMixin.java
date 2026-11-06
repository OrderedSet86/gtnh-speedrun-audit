package com.gtnhspeedrun.audit.mixins.late.gt;

import net.minecraft.item.ItemStack;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.gtnhspeedrun.audit.trackers.PatternSinks;

import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.metatileentity.MetaTileEntity;
import gregtech.common.tileentities.machines.MTEHatchCraftingInputME;

/**
 * Logs each pattern put into GT's Crafting Input Buffer (ME), or a pattern slot emptied. onPatternChange() gets only
 * the slot's new stack. onPatternChange() is private in GT 5.09.51 (pack 2.8.4) and public in 5.09.54, with one
 * signature.
 */
@Mixin(value = MTEHatchCraftingInputME.class, remap = false)
public class MTEHatchCraftingInputMEMixin {

    @Inject(method = "onPatternChange", at = @At("HEAD"), require = 1)
    private void audit$patternChange(int index, ItemStack newItem, CallbackInfo ci) { // noqa: NAR130
        final IGregTechTileEntity base = ((MetaTileEntity) (Object) this).getBaseMetaTileEntity();
        if (!(base != null && base.isServerSide())) {
            return;
        }
        PatternSinks.logPatternSlot(
            new PatternSinks.ProviderSlot(
                "MTEHatchCraftingInputME",
                base.getWorld().provider.dimensionId,
                base.getXCoord(),
                base.getYCoord(),
                base.getZCoord(),
                index),
            null,
            newItem);
    }
}
