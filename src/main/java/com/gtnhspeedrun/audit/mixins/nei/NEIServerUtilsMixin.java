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
import com.gtnhspeedrun.audit.snapshot.ItemKey;
import com.gtnhspeedrun.audit.snapshot.StackJson;
import com.gtnhspeedrun.audit.trackers.AuditSinks;

import codechicken.nei.NEIServerUtils;

/**
 * The decoded halves of the cheat actions — item, slot, mode, hour — after NEISPH read them off the wire.
 *
 * <p>
 * Everything here reads vanilla state only. NEI's own helpers (getSlotContents and friends) are not called: the
 * pack-2.8.4 NEI may lack any of them, and a NoSuchMethodError inside an injection would take the packet handler
 * down with it.
 */
@Mixin(value = NEIServerUtils.class, remap = false)
public class NEIServerUtilsMixin {

    /** HEAD→RETURN hand-off for a give. NEI packets run on the netty thread, and both halves run on the same one. */
    @Unique
    private static final ThreadLocal<Object[]> audit$giveStart = new ThreadLocal<>();

    @Inject(method = "givePlayerItem", at = @At("HEAD"), require = 1)
    private static void audit$giveHead(EntityPlayerMP player, ItemStack stack, boolean infinite, boolean doGive,
        CallbackInfo ci) {
        // Copy now: an infinite give hands the stack itself to addItemStackToInventory, which shrinks it.
        final ItemStack requested = stack == null ? null : stack.copy();
        audit$giveStart.set(new Object[] { requested, audit$countMatching(player, requested) });
    }

    /**
     * Logged at RETURN so the line carries what actually went in. A give into a full inventory inserts nothing
     * (NEI's client then fills open-container slots by set_slot and sends doGive=false just for the chat notice),
     * so {@code requested} alone overstates it.
     */
    @Inject(method = "givePlayerItem", at = @At("RETURN"), require = 1)
    private static void audit$give(EntityPlayerMP player, ItemStack stack, boolean infinite, boolean doGive,
        CallbackInfo ci) {
        final Object[] start = audit$giveStart.get();
        audit$giveStart.remove();
        final ItemStack requested = start == null ? stack : (ItemStack) start[0];
        final JsonObject extra = new JsonObject();
        extra.addProperty("doGive", doGive);
        if (requested != null && start != null) {
            extra.addProperty("inserted", audit$countMatching(player, requested) - (Integer) start[1]);
        }
        if (requested != null && requested.hasTagCompound()) {
            extra.add("stack", StackJson.of(requested));
        }
        AuditSinks.neiCheat(
            "give",
            player.getGameProfile()
                .getId()
                .toString(),
            player.getCommandSenderName(),
            requested == null ? null : ItemKey.base(requested),
            requested == null ? 0 : requested.stackSize,
            infinite ? "infinite" : null,
            extra);
    }

    @Inject(method = "setSlotContents", at = @At("HEAD"), require = 1)
    private static void audit$setSlot(EntityPlayer player, int slot, ItemStack item, boolean containerInv,
        CallbackInfo ci) {
        final JsonObject extra = new JsonObject();
        final ItemStack previous = audit$slotContents(player, slot, containerInv);
        if (previous != null) {
            extra.add("previous", StackJson.of(previous));
        }
        if (item != null && item.hasTagCompound()) {
            extra.add("stack", StackJson.of(item));
        }
        AuditSinks.neiCheat(
            "set_slot",
            player.getGameProfile()
                .getId()
                .toString(),
            player.getCommandSenderName(),
            item == null ? null : ItemKey.base(item),
            item == null ? 0 : item.stackSize,
            "slot=" + slot + (containerInv ? ",container" : ""),
            extra);
    }

    /** NEI's "delete all" — and its load-saved-state, which calls this before refilling by set_slot. */
    @Inject(method = "deleteAllItems", at = @At("HEAD"), require = 1)
    private static void audit$deleteAll(EntityPlayerMP player, CallbackInfo ci) {
        final JsonObject extra = new JsonObject();
        final JsonArray items = new JsonArray();
        int index = 0;
        for (Object o : player.openContainer.inventorySlots) {
            final JsonObject stack = StackJson.of(((Slot) o).getStack());
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
        AuditSinks.neiCheat(
            "delete_all_items",
            player.getGameProfile()
                .getId()
                .toString(),
            player.getCommandSenderName(),
            null,
            0,
            null,
            extra);
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

    /** Same addressing as NEIServerUtils.setSlotContents: -999 is the cursor stack. */
    @Unique
    private static ItemStack audit$slotContents(EntityPlayer player, int slot, boolean containerInv) {
        try {
            if (slot == -999) {
                return player.inventory.getItemStack();
            }
            if (containerInv) {
                return player.openContainer.getSlot(slot)
                    .getStack();
            }
            return player.inventory.getStackInSlot(slot);
        } catch (RuntimeException e) {
            return null; // NEI's own write will fail the same way; the line still records the attempt.
        }
    }

    /** Items in the main inventory that would stack with {@code like} — size ignored, NBT compared. */
    @Unique
    private static int audit$countMatching(EntityPlayer player, ItemStack like) {
        if (like == null || like.getItem() == null) {
            return 0;
        }
        int total = 0;
        for (ItemStack s : player.inventory.mainInventory) {
            if (s != null && s.isItemEqual(like) && ItemStack.areItemStackTagsEqual(s, like)) {
                total += s.stackSize;
            }
        }
        return total;
    }
}
