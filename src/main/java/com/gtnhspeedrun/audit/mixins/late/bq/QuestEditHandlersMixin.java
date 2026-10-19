package com.gtnhspeedrun.audit.mixins.late.bq;

import java.lang.invoke.MethodHandles;

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
 * Logs quest book changes made outside /bq_admin. BQ changes the quest book through the handlers of four packets:
 * quest edits (including edit-mode "complete"), chapter edits, whole-database imports and settings (the edit-mode
 * toggle). All four handlers have the private static {@code onServer(Tuple2<NBTTagCompound, EntityPlayerMP>)}
 * shape from BQ 3.7.15 through 3.8.87.
 */
@Mixin(value = { NetQuestEdit.class, NetChapterEdit.class, NetImport.class, NetSettingSync.class }, remap = false)
public class QuestEditHandlersMixin {

    @Inject(method = "onServer", at = @At("HEAD"), require = 1)
    private static void audit$edit(Tuple2<NBTTagCompound, EntityPlayerMP> message, CallbackInfo ci) { // noqa: NAR130
        // Mixin merges this handler into each target class, so lookupClass() returns the packet handler class
        BqActor.edit(
            MethodHandles.lookup()
                .lookupClass()
                .getSimpleName(),
            message == null ? null : message.getFirst(),
            message == null ? null : message.getSecond());
    }
}
