package com.gtnhspeedrun.audit.mixins.minecraft;

import net.minecraft.command.ICommandSender;
import net.minecraft.util.IChatComponent;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.gtnhspeedrun.audit.trackers.CommandOutputCapture;

/**
 * The three server-side command senders' chat sinks. A no-op unless CommandOutputCapture has an allowlisted
 * command open on this thread. Early config: vanilla classes.
 */
@Mixin({ net.minecraft.server.MinecraftServer.class, net.minecraft.network.rcon.RConConsoleSource.class })
public class ChatReceiversMixin {

    @Inject(method = "addChatMessage", at = @At("HEAD"), require = 1)
    private void audit$line(IChatComponent message, CallbackInfo ci) {
        CommandOutputCapture.line((ICommandSender) (Object) this, message);
    }
}
