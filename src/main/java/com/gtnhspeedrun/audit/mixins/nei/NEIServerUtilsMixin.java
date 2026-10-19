package com.gtnhspeedrun.audit.mixins.nei;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.gtnhspeedrun.audit.snapshot.StackJson;
import com.gtnhspeedrun.audit.trackers.NeiSinks;

import codechicken.nei.NEIServerUtils;

/**
 * Logs the decoded detail of each NEI cheat: the item, slot, game mode or hour that NEISPH read from the packet.
 *
 * <p>
 * The handlers read only vanilla state. The handlers call no NEI helper such as getSlotContents(), because the NEI of
 * pack 2.8.4 may lack any of those helpers. A NoSuchMethodError inside an injection would make the NEI packet handler
 * fail.
 */
@Mixin(value = NEIServerUtils.class, remap = false)
public class NEIServerUtilsMixin {

    /**
     * Hand-off from the HEAD to the RETURN of a give: {requested stack copy, matching count before the give}. NEI
     * handles packets on the netty thread, and both injections run on that thread.
     */
    @Unique
    private static final ThreadLocal<Object[]> audit$giveStart = new ThreadLocal<>(); // noqa: NAR130

    /** Copies the requested stack and counts the matching items in the main inventory before the give. */
    @Inject(method = "givePlayerItem", at = @At("HEAD"), require = 1)
    private static void audit$giveHead(EntityPlayerMP player, ItemStack stack, boolean infinite, // noqa: NAR130, NAR131
        boolean doGive, CallbackInfo ci) {
        // for an infinite give, NEI passes this stack to addItemStackToInventory(). That call shrinks the stack, so
        // copy the stack first
        final ItemStack requested = stack == null ? null : stack.copy();
        audit$giveStart.set(new Object[] { requested, audit$countMatching(player, requested) });
    }

    /**
     * Logs the give at RETURN, with the change in matching items in the main inventory. A give into a full inventory
     * inserts nothing. The NEI client then fills open-container slots by set_slot, and sends doGive=false only for the
     * chat notice. The requested count is then higher than the inserted count.
     */
    @Inject(method = "givePlayerItem", at = @At("RETURN"), require = 1)
    private static void audit$give(EntityPlayerMP player, ItemStack stack, boolean infinite, // noqa: NAR130, NAR131
        boolean doGive, CallbackInfo ci) {
        final Object[] start = audit$giveStart.get();
        audit$giveStart.remove();
        final ItemStack requested = start == null ? stack : (ItemStack) start[0];
        final JsonObject extra = new JsonObject();
        extra.addProperty("doGive", doGive);
        if (requested != null && start != null) {
            extra.addProperty("inserted", audit$countMatching(player, requested) - (Integer) start[1]);
        }
        if (requested != null && requested.hasTagCompound()) {
            extra.add("stack", StackJson.toJson(requested));
        }
        NeiSinks.NeiCheat.of("give")
            .player(player)
            .item(requested)
            .detail(infinite ? "infinite" : null)
            .extra(extra)
            .log();
    }

    /** Logs a set_slot cheat, with the slot's contents before the write. */
    @Inject(method = "setSlotContents", at = @At("HEAD"), require = 1)
    private static void audit$setSlot(EntityPlayer player, int slot, ItemStack item, // noqa: NAR130, NAR131
        boolean containerInv, CallbackInfo ci) {
        final JsonObject extra = new JsonObject();
        final ItemStack previous = audit$slotContents(player, slot, containerInv);
        if (previous != null) {
            extra.add("previous", StackJson.toJson(previous));
        }
        if (item != null && item.hasTagCompound()) {
            extra.add("stack", StackJson.toJson(item));
        }
        NeiSinks.NeiCheat.of("set_slot")
            .player(player)
            .item(item)
            .detail("slot=" + slot + (containerInv ? ",container" : ""))
            .extra(extra)
            .log();
    }

    /**
     * Logs NEI's "delete all items", with every stack in the open container. NEI's load of a saved state also calls
     * deleteAllItems(), and then refills the slots by set_slot.
     */
    @Inject(method = "deleteAllItems", at = @At("HEAD"), require = 1)
    private static void audit$deleteAll(EntityPlayerMP player, CallbackInfo ci) { // noqa: NAR130
        final JsonObject extra = new JsonObject();
        final JsonArray items = new JsonArray();
        int index = 0;
        for (Object slot : player.openContainer.inventorySlots) {
            final JsonObject stack = StackJson.toJson(((Slot) slot).getStack());
            if (stack != null) {
                stack.addProperty("slot", index);
                items.add(stack);
            }
            index++;
        }
        extra.addProperty(
            "container",
            player.openContainer.getClass()
                .getName());
        extra.add("items", items);
        NeiSinks.NeiCheat.of("delete_all_items")
            .player(player)
            .extra(extra)
            .log();
    }

    @Inject(method = "setGamemode", at = @At("HEAD"), require = 1)
    private static void audit$gamemode(EntityPlayerMP player, int mode, CallbackInfo ci) { // noqa: NAR130
        NeiSinks.NeiCheat.of("set_gamemode")
            .player(player)
            .detail("mode=" + mode)
            .log();
    }

    /** Logs a set_time cheat. NEI passes no player to setHourForward(), so the line has no uuid and no name. */
    @Inject(method = "setHourForward", at = @At("HEAD"), require = 1)
    private static void audit$setHour(World world, int hour, boolean notify, CallbackInfo ci) { // noqa: NAR130, NAR131
        NeiSinks.NeiCheat.of("set_time")
            .detail("hour=" + hour)
            .log();
    }

    @Inject(method = "healPlayer", at = @At("HEAD"), require = 1)
    private static void audit$heal(EntityPlayer player, CallbackInfo ci) { // noqa: NAR130
        NeiSinks.NeiCheat.of("heal")
            .player(player)
            .log();
    }

    /** Reads a slot by the addressing of NEIServerUtils.setSlotContents(). */
    @Unique
    private static ItemStack audit$slotContents(EntityPlayer player, int slot, boolean containerInv) { // noqa: NAR130
        final int CURSOR_SLOT = -999;
        try {
            if (slot == CURSOR_SLOT) {
                return player.inventory.getItemStack();
            }
            if (containerInv) {
                return player.openContainer.getSlot(slot)
                    .getStack();
            }
            return player.inventory.getStackInSlot(slot);
        } catch (RuntimeException e) { // noqa: NAR142, degrade over the open container of another mod
            return null; // NEI's write fails on this slot too. The attempt is still logged
        }
    }

    /** Counts the items in the main inventory with the item, damage and NBT of {@code like}. */
    @Unique
    private static int audit$countMatching(EntityPlayer player, ItemStack like) { // noqa: NAR130
        if (!(like != null && like.getItem() != null)) {
            return 0;
        }
        int total = 0;
        for (ItemStack stack : player.inventory.mainInventory) {
            if (stack != null && stack.isItemEqual(like) && ItemStack.areItemStackTagsEqual(stack, like)) {
                total += stack.stackSize;
            }
        }
        return total;
    }
}
