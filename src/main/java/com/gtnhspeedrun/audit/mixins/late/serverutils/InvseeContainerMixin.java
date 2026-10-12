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
 * /inv view (alias /inv edit): an op moving items in and out of another player's inventories. The record is per
 * click, not a before/after of the whole session — the target can use their own inventory while the view is
 * open, and a session diff would mix their moves in. One slotClick runs synchronously, so the slots that changed
 * across it are exactly what that click moved; each is tagged with whose inventory it belongs to.
 *
 * <p>
 * An online target's inventories are their live ones. An offline target's are a copy, written to their file only
 * when the view closes normally and only if the target is offline at that moment. If the target logs in first,
 * the write is skipped: what the viewer took is duplicated and what the viewer put in is lost. Each view therefore
 * gets an id (InvseeViews), which follows the target's logins, other views of the same target, and how the view
 * ended. Same class shape from ServerUtilities 2.2.2 (pack 2.8.4) through 2.4.13; both method names are listed
 * because the class is unmapped while its Container overrides are SRG-named in production.
 */
@Mixin(value = InvseeContainer.class, remap = false)
public class InvseeContainerMixin implements InvseeViewHolder {

    @Shadow
    @Final
    private ForgePlayer otherPlayer;

    @Unique
    private String audit$viewId;

    @Unique
    private int audit$depth;

    @Unique
    private List<ItemStack> audit$before;

    @Unique
    private ItemStack audit$cursorBefore;

    @Override
    public String audit$viewId() {
        return audit$viewId;
    }

    /**
     * /inv view builds the container before assigning it to the viewer, so the viewer's open container here is the
     * view this one replaces, if any. That one never gets onContainerClosed, so it is ended here.
     */
    @Inject(method = "<init>", at = @At("RETURN"), require = 1)
    private void audit$open(CallbackInfo ci) {
        final EntityPlayer viewer = ((ContainerBase) (Object) this).player;
        if (viewer == null || viewer.worldObj == null || viewer.worldObj.isRemote) {
            return;
        }
        if (viewer.openContainer instanceof InvseeViewHolder previous && previous != (Object) this) {
            InvseeViews.end(
                previous.audit$viewId(),
                "replaced",
                viewer.getUniqueID(),
                viewer.getCommandSenderName(),
                null,
                otherPlayer != null && otherPlayer.isOnline());
        }
        audit$viewId = InvseeViews.open(
            viewer.getUniqueID(),
            viewer.getCommandSenderName(),
            otherPlayer == null ? null : otherPlayer.getId(),
            otherPlayer == null ? null : otherPlayer.getName(),
            otherPlayer != null && otherPlayer.isOnline());
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
            boolean touchedTarget = false;
            for (int i = 0; i < changes.size(); i++) {
                touchedTarget |= "target".equals(
                    changes.get(i)
                        .getAsJsonObject()
                        .get("owner")
                        .getAsString());
            }
            InvseeViews.clicked(audit$viewId, touchedTarget);
            AuditSinks.invseeClick(
                audit$viewId,
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
        InvseeViews.end(
            audit$viewId,
            "closed",
            player.getUniqueID(),
            player.getCommandSenderName(),
            otherPlayer == null ? null : otherPlayer.getName(),
            otherPlayer != null && otherPlayer.isOnline());
    }
}
