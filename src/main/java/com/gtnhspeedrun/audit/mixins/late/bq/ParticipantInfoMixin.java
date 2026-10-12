package com.gtnhspeedrun.audit.mixins.late.bq;

import java.util.List;
import java.util.UUID;

import net.minecraft.entity.player.EntityPlayer;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.gtnhspeedrun.audit.compat.BqActor;

import betterquesting.api2.utils.ParticipantInfo;

/** Every BQ detection path builds one of these for the acting player right before it runs. */
@Mixin(value = ParticipantInfo.class, remap = false)
public class ParticipantInfoMixin {

    @Shadow
    @Final
    public EntityPlayer PLAYER;

    @Shadow
    @Final
    public UUID UUID;

    @Shadow
    @Final
    public List<UUID> ACTIVE_UUIDS;

    @Inject(method = "<init>", at = @At("RETURN"), require = 1)
    private void audit$built(EntityPlayer player, CallbackInfo ci) {
        BqActor.noteParticipant(PLAYER, UUID, ACTIVE_UUIDS);
    }
}
