package com.gtnhspeedrun.audit.mixins.minecraft;

import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.IChatComponent;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.gtnhspeedrun.audit.trackers.CommandOutputCapture;

/**
 * Passes the lines of a player's two chat paths to CommandOutputCapture. Vanilla commands call addChatMessage().
 * addChatComponentMessage() sends the packet directly, and NEI and other mods call addChatComponentMessage(). The
 * handlers are no-ops outside an allowlisted command. The mixin is in the early config because EntityPlayerMP is a
 * vanilla class.
 */
@Mixin(EntityPlayerMP.class)
public class EntityPlayerMPChatMixin {

    @Inject(method = "addChatMessage", at = @At("HEAD"), require = 1)
    private void audit$line(IChatComponent message, CallbackInfo ci) { // noqa: NAR130
        CommandOutputCapture.captureLine((ICommandSender) (Object) this, message);
    }

    @Inject(method = "addChatComponentMessage", at = @At("HEAD"), require = 1)
    private void audit$component(IChatComponent message, CallbackInfo ci) { // noqa: NAR130
        CommandOutputCapture.captureLine((ICommandSender) (Object) this, message);
    }
}
