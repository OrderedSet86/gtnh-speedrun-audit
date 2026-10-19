package com.gtnhspeedrun.audit.mixins.late.bq;

import java.util.UUID;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.gtnhspeedrun.audit.compat.BqActor;

import betterquesting.api.questing.tasks.ITask;
import bq_standard.tasks.base.TaskBase;

/**
 * Passes each bq_standard task completion to BqActor. bq_standard completes a task of every type through
 * setComplete(), once per party member.
 */
@Mixin(value = TaskBase.class, remap = false)
public class TaskBaseMixin {

    @Inject(method = "setComplete", at = @At("HEAD"), require = 1)
    private void audit$completing(UUID uuid, CallbackInfo ci) { // noqa: NAR130
        final ITask self = (ITask) (Object) this;
        if (uuid != null && !self.isComplete(uuid)) {
            BqActor.taskCompleting(self, uuid);
        }
    }
}
