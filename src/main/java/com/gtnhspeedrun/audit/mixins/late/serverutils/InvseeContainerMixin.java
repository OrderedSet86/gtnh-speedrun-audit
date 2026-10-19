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
import com.gtnhspeedrun.audit.trackers.InvseeViewHolder;
import com.gtnhspeedrun.audit.trackers.InvseeViews;

import serverutils.invsee.InvseeContainer;
import serverutils.lib.data.ForgePlayer;
import serverutils.lib.gui.ContainerBase;

/**
 * Logs /inv view (alias /inv edit): an op moving items into and out of another player's inventories. The log has one
 * line per click and no before and after of the whole view. The target can use their inventory while the view is open,
 * so a diff of the whole view would contain the moves of the target. slotClick() runs to completion on the server
 * thread, so the slots that changed across the outermost call are the slots changed by the click. Each changed slot is
 * tagged as the viewer's or the target's.
 *
 * <p>
 * An online target's inventories are the live inventories. An offline target's inventories are a copy. The copy is
 * written to the target's file only when the view closes normally, and only if the target is offline at that moment.
 * If the target logs in first, the write is skipped: what the viewer took is duplicated and what the viewer put in is
 * lost. Each view therefore gets an id from InvseeViews. InvseeViews logs the target's logins, other views of the same
 * target and how the view ended under that id. The class shape is the same from ServerUtilities 2.2.2 (pack 2.8.4)
 * through 2.4.13. Both the MCP and the SRG name are listed for each Container method, because InvseeContainer is
 * unmapped and its overrides of Container methods have SRG names in production.
 */
@Mixin(value = InvseeContainer.class, remap = false)
public class InvseeContainerMixin implements InvseeViewHolder {

    @Shadow
    @Final
    private ForgePlayer otherPlayer;

    @Unique
    private String audit$viewId; // noqa: NAR130

    @Unique
    private int audit$depth; // noqa: NAR130

    @Unique
    private List<ItemStack> audit$before; // noqa: NAR130

    @Unique
    private ItemStack audit$cursorBefore; // noqa: NAR130

    @Override
    public String audit$viewId() {
        return audit$viewId;
    }

    /**
     * ServerUtilities builds the /inv view container before setting the viewer's open container. At this point the
     * viewer's open container is therefore the /inv view being replaced, if there is one. The replaced view never gets
     * onContainerClosed(), so this hook ends the replaced view.
     */
    @Inject(method = "<init>", at = @At("RETURN"), require = 1)
    private void audit$open(CallbackInfo ci) { // noqa: NAR130
        final EntityPlayer viewer = ((ContainerBase) (Object) this).player;
        if (!(viewer != null && viewer.worldObj != null && !viewer.worldObj.isRemote)) {
            return;
        }
        if (viewer.openContainer instanceof InvseeViewHolder previous && previous != (Object) this) {
            InvseeViews.end(
                previous.audit$viewId(),
                new InvseeViews.Party(viewer.getUniqueID(), viewer.getCommandSenderName()),
                new InvseeViews.ViewEnd("replaced", null, otherPlayer != null && otherPlayer.isOnline()));
        }
        audit$viewId = InvseeViews.open(
            new InvseeViews.Party(viewer.getUniqueID(), viewer.getCommandSenderName()),
            new InvseeViews.Party(
                otherPlayer == null ? null : otherPlayer.getId(),
                otherPlayer == null ? null : otherPlayer.getName()),
            otherPlayer != null && otherPlayer.isOnline());
    }

    /** Copies every slot and the cursor stack before the outermost slotClick() of a click. */
    @Inject(method = { "slotClick", "func_75144_a" }, at = @At("HEAD"), require = 1)
    private void audit$clickHead(int slotId, int button, int mode, EntityPlayer player, // noqa: NAR130, NAR131
        CallbackInfoReturnable<ItemStack> cir) {
        // vanilla's shift-click can re-enter slotClick() through retrySlotClick(). Only the outermost call is one click
        if (audit$depth++ > 0 || player.worldObj.isRemote) {
            return;
        }
        final List<?> slots = ((Container) (Object) this).inventorySlots;
        final List<ItemStack> before = new ArrayList<>(slots.size());
        for (Object slot : slots) {
            before.add(StackJson.copy(((Slot) slot).getStack()));
        }
        audit$before = before;
        audit$cursorBefore = StackJson.copy(player.inventory.getItemStack());
    }

    /**
     * Logs each slot that changed across the click, with the cursor stack before and after. Nothing is logged for a
     * click that changed no slot and not the cursor.
     */
    @Inject(method = { "slotClick", "func_75144_a" }, at = @At("RETURN"), require = 1)
    private void audit$clickReturn(int slotId, int button, int mode, EntityPlayer player, // noqa: NAR130, NAR131
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
            change.add("before", StackJson.toJson(was));
            change.add("after", StackJson.toJson(now));
            changes.add(change);
        }
        final ItemStack cursorNow = player.inventory.getItemStack();
        final boolean cursorChanged = !ItemStack.areItemStacksEqual(audit$cursorBefore, cursorNow);
        if (changes.size() > 0 || cursorChanged) {
            boolean touchedTarget = false;
            for (int i = 0; i < changes.size(); i++) {
                touchedTarget |= "target".equals(
                    changes.get(i)
                        .getAsJsonObject()
                        .get("owner")
                        .getAsString());
            }
            InvseeViews.countClick(audit$viewId, touchedTarget);
            AuditSinks.InvseeClick.of(audit$viewId, player)
                .target(
                    otherPlayer == null ? null : otherPlayer.getName(),
                    otherPlayer == null ? null : String.valueOf(otherPlayer.getId()),
                    otherPlayer != null && otherPlayer.isOnline())
                .click(slotId, button, mode)
                .changes(changes)
                .cursor(StackJson.toJson(audit$cursorBefore), StackJson.toJson(cursorNow))
                .log();
        }
        audit$before = null;
        audit$cursorBefore = null;
    }

    @Inject(method = { "onContainerClosed", "func_75134_a" }, at = @At("HEAD"), require = 1)
    private void audit$close(EntityPlayer player, CallbackInfo ci) { // noqa: NAR130
        if (player.worldObj.isRemote) {
            return;
        }
        InvseeViews.end(
            audit$viewId,
            new InvseeViews.Party(player.getUniqueID(), player.getCommandSenderName()),
            new InvseeViews.ViewEnd(
                "closed",
                otherPlayer == null ? null : otherPlayer.getName(),
                otherPlayer != null && otherPlayer.isOnline()));
    }
}
