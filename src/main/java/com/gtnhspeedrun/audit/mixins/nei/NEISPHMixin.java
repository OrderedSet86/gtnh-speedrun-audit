package com.gtnhspeedrun.audit.mixins.nei;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;
import net.minecraft.network.play.INetHandlerPlayServer;
import net.minecraft.tileentity.TileEntityMobSpawner;
import net.minecraft.world.World;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.google.gson.JsonObject;
import com.gtnhspeedrun.audit.trackers.NeiSinks;

import codechicken.lib.packet.PacketCustom;
import codechicken.lib.vec.BlockCoord;
import codechicken.nei.NEISPH;

/**
 * Logs every NEI packet, and the decoded detail of the NEI cheats with a handler method in NEISPH. Every NEI cheat goes
 * through NEISPH.handlePacket(). {@link NEIServerUtilsMixin} logs the cheats that NEISPH passes to NEIServerUtils. The
 * private handler methods have the same signatures from NEI 2.8.19 through 2.8.160, pack 2.8.4's 2.8.44 included.
 */
@Mixin(value = NEISPH.class, remap = false)
public class NEISPHMixin {

    /**
     * Runs at HEAD, before {@code NEIServerConfig.authenticatePacket}, so a denied attempt is logged too. The packet
     * is a live stream. Do NOT read from the packet here.
     */
    @Inject(method = "handlePacket", at = @At("HEAD"), require = 1)
    private void audit$logPacket(PacketCustom packet, EntityPlayerMP sender, // noqa: NAR130, NAR131
        INetHandlerPlayServer netHandler, CallbackInfo ci) {
        NeiSinks.beginPacket(sender);
        NeiSinks.logNeiPacket(
            sender.getGameProfile()
                .getId()
                .toString(),
            sender.getCommandSenderName(),
            packet.getType());
    }

    /** Clears the packet sender in NeiSinks at the end of handlePacket(). */
    @Inject(method = "handlePacket", at = @At("RETURN"), require = 1)
    private void audit$endPacket(PacketCustom packet, EntityPlayerMP sender, // noqa: NAR130, NAR131
        INetHandlerPlayServer netHandler, CallbackInfo ci) {
        NeiSinks.endPacket();
    }

    /** Logs NEI's creative+ inventory, a 54-slot stash of items, opened or closed. */
    @Inject(method = "processCreativeInv", at = @At("HEAD"), require = 1)
    private static void audit$creativePlus(EntityPlayerMP sender, boolean open, CallbackInfo ci) { // noqa: NAR130
        NeiSinks.NeiCheat.of("creative_plus")
            .player(sender)
            .detail(open ? "open" : "close")
            .log();
    }

    /** Logs an enchantment added to or removed from the item in NEI's enchantment GUI, at any level. */
    @Inject(method = "modifyEnchantment", at = @At("HEAD"), require = 1)
    private void audit$modifyEnchantment(EntityPlayerMP player, int enchantment, int level, // noqa: NAR130, NAR131
        boolean add, CallbackInfo ci) {
        final JsonObject extra = new JsonObject();
        extra.addProperty("enchantment", enchantment);
        extra.addProperty("level", level);
        extra.addProperty("add", add);
        NeiSinks.NeiCheat.of("modify_enchantment")
            .player(player)
            .extra(extra)
            .log();
    }

    /** Logs a mob spawner's new mob. NEI checks no permission for this packet. */
    @Inject(method = "handleMobSpawnerID", at = @At("HEAD"), require = 1)
    private void audit$setSpawnerMob(World world, BlockCoord coord, String mobType, // noqa: NAR130, NAR131
        CallbackInfo ci) {
        final JsonObject extra = new JsonObject();
        extra.addProperty("dim", world.provider.dimensionId);
        extra.addProperty("x", coord.x);
        extra.addProperty("y", coord.y);
        extra.addProperty("z", coord.z);
        extra.addProperty("isSpawner", world.getTileEntity(coord.x, coord.y, coord.z) instanceof TileEntityMobSpawner);
        NeiSinks.NeiCheat.of("set_spawner_mob")
            .detail(mobType)
            .extra(extra)
            .log();
    }

    /**
     * Logs a ghost-slot write, then makes the original putStack() call. NEI writes only into a SlotDummy, so every
     * logged write takes effect.
     */
    @Redirect(
        method = "handleDummySlotSet",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/inventory/Slot;putStack(Lnet/minecraft/item/ItemStack;)V",
            remap = true),
        require = 1)
    private void audit$setDummySlot(Slot slot, ItemStack stack) { // noqa: NAR130
        NeiSinks.logDummySlot(slot, stack);
        slot.putStack(stack);
    }
}
