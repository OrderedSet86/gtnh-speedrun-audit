package com.gtnhspeedrun.audit.mixins.late.ae2;

import net.minecraft.entity.player.EntityPlayerMP;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.gtnhspeedrun.audit.trackers.PatternSinks;

import appeng.container.implementations.ContainerInterfaceTerminal;
import appeng.helpers.InventoryAction;

/**
 * Marks the player of an Interface Terminal action. The terminal moves patterns in and out of providers anywhere on the
 * network, with no GUI click on the provider, so the provider's slot change gets the player from here.
 */
@Mixin(value = ContainerInterfaceTerminal.class, remap = false)
public class ContainerInterfaceTerminalMixin {

    /** Mark {@code player} as the player of each provider slot change during the action. */
    @Inject(method = "doAction", at = @At("HEAD"), require = 1)
    private void audit$beginAction(EntityPlayerMP player, InventoryAction action, // noqa: NAR130, NAR131
        int slot, long id, CallbackInfo ci) {
        PatternSinks.beginPlayerAction(player);
    }

    /** Clear the acting player at the end of the action. */
    @Inject(method = "doAction", at = @At("RETURN"), require = 1)
    private void audit$endAction(EntityPlayerMP player, InventoryAction action, // noqa: NAR130, NAR131
        int slot, long id, CallbackInfo ci) {
        PatternSinks.endPlayerAction();
    }
}
