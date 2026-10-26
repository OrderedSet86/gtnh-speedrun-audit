package com.gtnhspeedrun.audit.mixins.late.serverutils;

import net.minecraft.entity.Entity;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import com.gtnhspeedrun.audit.trackers.TeleportSinks;

import serverutils.data.TeleportType;
import serverutils.lib.math.TeleporterDimPos;
import serverutils.task.TeleportTask;

/**
 * Logs each ServerUtilities teleport of a player by {@code /home}, {@code /warp}, {@code /spawn}, {@code /back},
 * {@code /tpaccept} and {@code /rtp}. Each of these commands runs a TeleportTask, at once or after the warmup. A
 * teleport whose warmup is cancelled by a move or a hit is not logged.
 *
 * <p>
 * In execute(), the first TeleporterDimPos.teleport() call moves the entity that the player rides, and only when the
 * player rides one. The second call moves the player. The fields and the call order are the same from ServerUtilities
 * 2.1.40 (pack 2.7.4) through 2.4.21.
 */
@Mixin(value = TeleportTask.class, remap = false)
public class TeleportTaskMixin {

    @Shadow
    @Final
    private TeleportType teleportType;

    @Shadow
    @Final
    private int startSeconds;

    /**
     * Logs the teleport, then moves the player. A move to another dimension logs dim_change inside teleport(), so the
     * teleport line is logged first.
     */
    @Redirect(
        method = "execute",
        at = @At(
            value = "INVOKE",
            target = "Lserverutils/lib/math/TeleporterDimPos;teleport(Lnet/minecraft/entity/Entity;)Lnet/minecraft/entity/Entity;",
            ordinal = 1),
        require = 1)
    private Entity audit$teleportPlayer(TeleporterDimPos destination, Entity entity) { // noqa: NAR130
        TeleportSinks.logTeleport(
            entity,
            new TeleportSinks.Move(
                teleportType.name(),
                startSeconds,
                destination.dim,
                destination.posX,
                destination.posY,
                destination.posZ));
        return destination.teleport(entity);
    }
}
