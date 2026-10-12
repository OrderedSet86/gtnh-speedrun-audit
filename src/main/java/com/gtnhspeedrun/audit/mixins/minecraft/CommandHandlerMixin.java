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

/** Brackets one command's execution so CommandOutputCapture can keep its replies. Early config: vanilla class. */
@Mixin(CommandHandler.class)
public class CommandHandlerMixin {

    @Inject(method = "executeCommand", at = @At("HEAD"), require = 1)
    private void audit$begin(ICommandSender sender, String line, CallbackInfoReturnable<Integer> cir) {
        ICommand resolved = null;
        try {
            String name = line == null ? "" : line.trim();
            if (name.startsWith("/")) {
                name = name.substring(1);
            }
            final Map<?, ?> commands = ((CommandHandler) (Object) this).getCommands();
            resolved = (ICommand) commands.get(name.split(" ")[0]);
        } catch (RuntimeException ignored) {
            // Unresolved: the typed name is matched instead.
        }
        CommandOutputCapture.begin(sender, line, resolved);
    }

    @Inject(method = "executeCommand", at = @At("RETURN"), require = 1)
    private void audit$end(ICommandSender sender, String line, CallbackInfoReturnable<Integer> cir) {
        CommandOutputCapture.end();
    }
}
