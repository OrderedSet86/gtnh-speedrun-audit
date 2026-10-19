package com.gtnhspeedrun.audit.mixins.minecraft;

import net.minecraft.command.ICommandSender;
import net.minecraft.network.rcon.RConConsoleSource;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.IChatComponent;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.gtnhspeedrun.audit.trackers.CommandOutputCapture;

/**
 * Passes the chat lines that the server console and RCon receive to CommandOutputCapture. With EntityPlayerMP, the
 * console and RCon are the command senders whose replies are captured. The replies to a command block are not captured.
 * The handler is a no-op unless CommandOutputCapture has an allowlisted command open on this thread. The mixin is in
 * the early config because the targets are vanilla classes.
 */
@Mixin({ MinecraftServer.class, RConConsoleSource.class })
public class ChatReceiversMixin {

    @Inject(method = "addChatMessage", at = @At("HEAD"), require = 1)
    private void audit$line(IChatComponent message, CallbackInfo ci) { // noqa: NAR130
        CommandOutputCapture.captureLine((ICommandSender) (Object) this, message);
    }
}
