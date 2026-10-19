package com.gtnhspeedrun.audit.mixins.minecraft;

import java.util.Map;

import net.minecraft.command.CommandHandler;
import net.minecraft.command.ICommand;
import net.minecraft.command.ICommandSender;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.gtnhspeedrun.audit.trackers.CommandOutputCapture;

/**
 * Marks the start and the end of each command's execution for CommandOutputCapture, so each reply goes to the log line
 * of the reply's command. The mixin is in the early config because CommandHandler is a vanilla class.
 */
@Mixin(CommandHandler.class)
public class CommandHandlerMixin {

    @Inject(method = "executeCommand", at = @At("HEAD"), require = 1)
    private void audit$begin(ICommandSender sender, String line, CallbackInfoReturnable<Integer> cir) { // noqa: NAR130
        ICommand resolved = null;
        try {
            String name = line == null ? "" : line.trim();
            if (name.startsWith("/")) {
                name = name.substring(1);
            }
            final Map<?, ?> commands = ((CommandHandler) (Object) this).getCommands();
            resolved = (ICommand) commands.get(name.split(" ")[0]);
        } catch (RuntimeException ignored) { // noqa: NAR142, degrade over a command map that other mods fill
            // resolved stays null, and CommandOutputCapture matches the typed name
        }
        CommandOutputCapture.begin(sender, line, resolved);
    }

    @Inject(method = "executeCommand", at = @At("RETURN"), require = 1)
    private void audit$end(ICommandSender sender, String line, CallbackInfoReturnable<Integer> cir) { // noqa: NAR130
        CommandOutputCapture.end();
    }
}
