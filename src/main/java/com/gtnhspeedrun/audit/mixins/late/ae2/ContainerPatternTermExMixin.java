package com.gtnhspeedrun.audit.mixins.late.ae2;

import java.util.List;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.inventory.Container;
import net.minecraft.item.ItemStack;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.gtnhspeedrun.audit.compat.Ae2PatternEvents;
import com.gtnhspeedrun.audit.trackers.ContainerSlots;

import appeng.container.AEBaseContainer;
import appeng.container.implementations.ContainerPatternTermEx;

/**
 * Logs each pattern encoded in the processing pattern terminal of AE2 rv3-beta-695 (pack 2.8.4). In that version,
 * ContainerPatternTermEx is a class separate from ContainerPatternTerm. On current AE2, ContainerPatternTermEx is a
 * subclass of ContainerPatternTerm with no encode() method. There {@link ContainerPatternTermMixin} logs the encode,
 * and the injections below have no target. {@code require = 0} allows the missing target.
 */
@Mixin(value = ContainerPatternTermEx.class, remap = false)
public class ContainerPatternTermExMixin {

    @Unique
    private List<ItemStack> audit$beforeEncode; // noqa: NAR130

    @Inject(method = "encode", at = @At("HEAD"), require = 0)
    private void audit$encodeHead(CallbackInfo ci) { // noqa: NAR130
        audit$beforeEncode = ContainerSlots.copySlots((Container) (Object) this);
    }

    @Inject(method = "encode", at = @At("RETURN"), require = 0)
    private void audit$encodeReturn(CallbackInfo ci) { // noqa: NAR130
        if (audit$beforeEncode != null
            && ((AEBaseContainer) (Object) this).getPlayerInv().player instanceof EntityPlayerMP player) {
            Ae2PatternEvents.logEncode((Container) (Object) this, audit$beforeEncode, player);
        }
        audit$beforeEncode = null;
    }
}
