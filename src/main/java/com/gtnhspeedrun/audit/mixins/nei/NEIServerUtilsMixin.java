package com.gtnhspeedrun.audit.mixins.nei;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.gtnhspeedrun.audit.snapshot.ItemKey;
import com.gtnhspeedrun.audit.trackers.AuditSinks;

import codechicken.nei.NEIServerUtils;

/** The decoded halves of the cheat actions — item, slot, mode, hour — after NEISPH read them off the wire. */
@Mixin(value = NEIServerUtils.class, remap = false)
public class NEIServerUtilsMixin {

    @Inject(method = "givePlayerItem", at = @At("HEAD"), require = 1)
    private static void audit$give(EntityPlayerMP player, ItemStack stack, boolean infinite, boolean doGive,
        CallbackInfo ci) {
        AuditSinks.neiCheat(
            "give",
            player.getGameProfile()
                .getId()
                .toString(),
            player.getCommandSenderName(),
            stack == null ? null : ItemKey.base(stack),
            stack == null ? 0 : stack.stackSize,
            infinite ? "infinite" : null);
    }

    @Inject(method = "setSlotContents", at = @At("HEAD"), require = 1)
    private static void audit$setSlot(EntityPlayer player, int slot, ItemStack item, boolean containerInv,
        CallbackInfo ci) {
        AuditSinks.neiCheat(
            "set_slot",
            player.getGameProfile()
                .getId()
                .toString(),
            player.getCommandSenderName(),
            item == null ? null : ItemKey.base(item),
            item == null ? 0 : item.stackSize,
            "slot=" + slot + (containerInv ? ",container" : ""));
    }

    @Inject(method = "deleteAllItems", at = @At("HEAD"), require = 1)
    private static void audit$deleteAll(EntityPlayerMP player, CallbackInfo ci) {
        AuditSinks.neiCheat(
            "delete_all_items",
            player.getGameProfile()
                .getId()
                .toString(),
            player.getCommandSenderName(),
            null,
            0,
            null);
    }

    @Inject(method = "setGamemode", at = @At("HEAD"), require = 1)
    private static void audit$gamemode(EntityPlayerMP player, int mode, CallbackInfo ci) {
        AuditSinks.neiCheat(
            "set_gamemode",
            player.getGameProfile()
                .getId()
                .toString(),
            player.getCommandSenderName(),
            null,
            0,
            "mode=" + mode);
    }

    @Inject(method = "setHourForward", at = @At("HEAD"), require = 1)
    private static void audit$setHour(World world, int hour, boolean notify, CallbackInfo ci) {
        AuditSinks.neiCheat("set_time", null, null, null, 0, "hour=" + hour);
    }

    @Inject(method = "healPlayer", at = @At("HEAD"), require = 1)
    private static void audit$heal(EntityPlayer player, CallbackInfo ci) {
        AuditSinks.neiCheat(
            "heal",
            player.getGameProfile()
                .getId()
                .toString(),
            player.getCommandSenderName(),
            null,
            0,
            null);
    }
}
