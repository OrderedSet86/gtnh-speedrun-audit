package com.gtnhspeedrun.audit.mixins.late.serverutils;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.gtnhspeedrun.audit.snapshot.StackJson;
import com.gtnhspeedrun.audit.trackers.AuditSinks;

import serverutils.invsee.InvseeContainer;
import serverutils.lib.data.ForgePlayer;

/**
 * /inv view (alias /inv edit): an op moving items in and out of another player's inventories. The record is per
 * click, not a before/after of the whole session — the target can use their own inventory while the view is
 * open, and a session diff would mix their moves in. One slotClick runs synchronously, so the slots that changed
 * across it are exactly what that click moved; each is tagged with whose inventory it belongs to.
 *
 * <p>
 * An online target's main inventory is their live one. An offline target's is a copy that ServerUtilities writes
 * back to their saved data when the view closes, so a target who logs in meanwhile can have their own moves
 * overwritten — the close line flags that case. Same class shape from ServerUtilities 2.2.2 (pack 2.8.4) through
 * 2.4.13; both method names are listed because the class is unmapped while its Container overrides are SRG-named
 * in production.
 */
@Mixin(value = InvseeContainer.class, remap = false)
public class InvseeContainerMixin {

    @Shadow
    @Final
    private ForgePlayer otherPlayer;

    @Unique
    private boolean audit$targetOnlineAtOpen;

    @Unique
    private int audit$depth;

    @Unique
    private List<ItemStack> audit$before;

    @Unique
    private ItemStack audit$cursorBefore;

    @Inject(method = "<init>", at = @At("RETURN"), require = 1)
    private void audit$open(CallbackInfo ci) {
        audit$targetOnlineAtOpen = otherPlayer != null && otherPlayer.isOnline();
    }

    @Inject(method = { "slotClick", "func_75144_a" }, at = @At("HEAD"), require = 1)
    private void audit$clickHead(int slotId, int button, int mode, EntityPlayer player,
        CallbackInfoReturnable<ItemStack> cir) {
        // Vanilla's shift-click can re-enter slotClick (retrySlotClick); only the outermost call is one click.
        if (audit$depth++ > 0 || player.worldObj.isRemote) {
            return;
        }
        final List<?> slots = ((Container) (Object) this).inventorySlots;
        final List<ItemStack> before = new ArrayList<>(slots.size());
        for (Object o : slots) {
            before.add(StackJson.copy(((Slot) o).getStack()));
        }
        audit$before = before;
        audit$cursorBefore = StackJson.copy(player.inventory.getItemStack());
    }

    @Inject(method = { "slotClick", "func_75144_a" }, at = @At("RETURN"), require = 1)
    private void audit$clickReturn(int slotId, int button, int mode, EntityPlayer player,
        CallbackInfoReturnable<ItemStack> cir) {
        if (--audit$depth > 0 || player.worldObj.isRemote || audit$before == null) {
            return;
        }
        final List<?> slots = ((Container) (Object) this).inventorySlots;
        final JsonArray changes = new JsonArray();
        for (int i = 0; i < slots.size() && i < audit$before.size(); i++) {
            final Slot slot = (Slot) slots.get(i);
            final ItemStack was = audit$before.get(i);
            final ItemStack now = slot.getStack();
            if (ItemStack.areItemStacksEqual(was, now)) {
                continue;
            }
            final JsonObject change = new JsonObject();
            change.addProperty("containerSlot", i);
            change.addProperty("owner", slot.inventory == player.inventory ? "viewer" : "target");
            change.addProperty(
                "inventory",
                slot.inventory.getClass()
                    .getSimpleName());
            change.addProperty("index", slot.getSlotIndex());
            change.add("before", StackJson.of(was));
            change.add("after", StackJson.of(now));
            changes.add(change);
        }
        final ItemStack cursorNow = player.inventory.getItemStack();
        final boolean cursorChanged = !ItemStack.areItemStacksEqual(audit$cursorBefore, cursorNow);
        if (changes.size() > 0 || cursorChanged) {
            AuditSinks.invseeClick(
                player,
                otherPlayer == null ? null : otherPlayer.getName(),
                otherPlayer == null ? null : String.valueOf(otherPlayer.getId()),
                otherPlayer != null && otherPlayer.isOnline(),
                slotId,
                button,
                mode,
                changes,
                StackJson.of(audit$cursorBefore),
                StackJson.of(cursorNow));
        }
        audit$before = null;
        audit$cursorBefore = null;
    }

    @Inject(method = { "onContainerClosed", "func_75134_a" }, at = @At("HEAD"), require = 1)
    private void audit$close(EntityPlayer player, CallbackInfo ci) {
        if (player.worldObj.isRemote) {
            return;
        }
        final boolean onlineNow = otherPlayer != null && otherPlayer.isOnline();
        AuditSinks.invseeClose(
            player,
            otherPlayer == null ? null : otherPlayer.getName(),
            otherPlayer == null ? null : String.valueOf(otherPlayer.getId()),
            audit$targetOnlineAtOpen,
            onlineNow);
    }
}
