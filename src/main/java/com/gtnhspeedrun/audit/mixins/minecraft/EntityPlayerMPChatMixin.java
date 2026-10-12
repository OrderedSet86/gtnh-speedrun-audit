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
 * A player's two chat paths: addChatMessage (what vanilla commands use) and addChatComponentMessage (sends the
 * packet directly; NEI and others use it). A no-op outside an allowlisted command. Early config: vanilla class.
 */
@Mixin(EntityPlayerMP.class)
public class EntityPlayerMPChatMixin {

    @Inject(method = "addChatMessage", at = @At("HEAD"), require = 1)
    private void audit$line(IChatComponent message, CallbackInfo ci) {
        CommandOutputCapture.line((ICommandSender) (Object) this, message);
    }

    @Inject(method = "addChatComponentMessage", at = @At("HEAD"), require = 1)
    private void audit$component(IChatComponent message, CallbackInfo ci) {
        CommandOutputCapture.line((ICommandSender) (Object) this, message);
    }
}
