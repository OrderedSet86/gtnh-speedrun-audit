package com.gtnhspeedrun.audit.mixins.late.ae2;

import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.gtnhspeedrun.audit.trackers.PatternSinks;

import appeng.api.util.DimensionalCoord;
import appeng.helpers.DualityInterface;
import appeng.tile.inventory.AppEngInternalInventory;
import appeng.tile.inventory.InvOperation;

/**
 * Logs each pattern put into or taken out of an ME interface, block or part, and the AE2 Fluid Crafting interfaces
 * built on DualityInterface. AE2 loads the slots from NBT with no change callback, so no line is logged when a chunk
 * loads. The field and the callback are the same in AE2 rv3-beta-695 (pack 2.8.4) and rv3-beta-1073.
 */
@Mixin(value = DualityInterface.class, remap = false)
public class DualityInterfaceMixin {

    @Shadow
    @Final
    private AppEngInternalInventory patterns;

    /** Logs each change of a slot of {@code patterns}, other than a {@code markDirty} operation. */
    @Inject(method = "onChangeInventory", at = @At("HEAD"), require = 1)
    private void audit$patternChange(IInventory inventory, int slot, InvOperation operation, // noqa: NAR130, NAR131
        ItemStack removed, ItemStack added, CallbackInfo ci) {
        if (!(inventory == patterns && operation != InvOperation.markDirty && (removed != null || added != null))) {
            return;
        }
        final DualityInterface duality = (DualityInterface) (Object) this;
        final DimensionalCoord location = duality.getLocation();
        if (!(location != null && location.getWorld() != null && !location.getWorld().isRemote)) {
            return;
        }
        PatternSinks.logPatternSlot(
            new PatternSinks.ProviderSlot(
                duality.getHost()
                    .getClass()
                    .getSimpleName(),
                location.getDimension(),
                location.x,
                location.y,
                location.z,
                slot),
            removed,
            added);
    }
}
