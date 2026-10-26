package com.gtnhspeedrun.audit.mixins.late.serverutils;

import net.minecraft.entity.Entity;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import com.gtnhspeedrun.audit.trackers.TeleportSinks;

import serverutils.command.tp.CmdTplast;
import serverutils.lib.math.TeleporterDimPos;

/**
 * Logs each {@code /tpl} teleport, to coordinates or to another player's last position. {@code /tpl} moves the player
 * with TeleporterDimPos.teleport() directly and runs no TeleportTask, so {@link TeleportTaskMixin} does not log a
 * {@code /tpl} teleport. Both teleport() calls in processCommand() move a player. processCommand() overrides a vanilla
 * ICommand method, so in production processCommand() has the SRG name func_71515_b.
 */
@Mixin(value = CmdTplast.class, remap = false)
public class CmdTplastMixin {

    @Redirect(
        method = { "processCommand", "func_71515_b" },
        at = @At(
            value = "INVOKE",
            target = "Lserverutils/lib/math/TeleporterDimPos;teleport(Lnet/minecraft/entity/Entity;)Lnet/minecraft/entity/Entity;"),
        require = 1)
    private Entity audit$teleportPlayer(TeleporterDimPos destination, Entity entity) { // noqa: NAR130
        TeleportSinks.logTeleport(
            entity,
            new TeleportSinks.Move("TPL", 0, destination.dim, destination.posX, destination.posY, destination.posZ));
        return destination.teleport(entity);
    }
}
