package com.gtnhspeedrun.audit.mixins.late.bq;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.NBTTagCompound;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.gtnhspeedrun.audit.compat.BqActor;

import betterquesting.api2.utils.Tuple2;
import betterquesting.network.handlers.NetChapterEdit;
import betterquesting.network.handlers.NetImport;
import betterquesting.network.handlers.NetQuestEdit;
import betterquesting.network.handlers.NetSettingSync;

/**
 * The quest book changes outside /bq_admin through these packets: quest edits (including edit-mode "complete"),
 * chapter edits, whole-database imports and settings (the edit-mode toggle). All four share the private static
 * {@code onServer(Tuple2<NBTTagCompound, EntityPlayerMP>)} shape from BQ 3.7.15 through 3.8.87.
 */
@Mixin(value = { NetQuestEdit.class, NetChapterEdit.class, NetImport.class, NetSettingSync.class }, remap = false)
public class QuestEditHandlersMixin {

    @Inject(method = "onServer", at = @At("HEAD"), require = 1)
    private static void audit$edit(Tuple2<NBTTagCompound, EntityPlayerMP> message, CallbackInfo ci) {
        // Merged into each target, so the lookup class is the handler this copy landed in.
        BqActor.edit(
            java.lang.invoke.MethodHandles.lookup()
                .lookupClass()
                .getSimpleName(),
            message == null ? null : message.getFirst(),
            message == null ? null : message.getSecond());
    }
}
