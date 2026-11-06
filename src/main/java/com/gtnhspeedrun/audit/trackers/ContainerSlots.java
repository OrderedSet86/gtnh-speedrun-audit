package com.gtnhspeedrun.audit.trackers;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.gtnhspeedrun.audit.snapshot.StackJson;

/** Copy the slots of an open container before a click, and list the slots that the click changed. */
public final class ContainerSlots {

    private ContainerSlots() {}

    public static List<ItemStack> copySlots(Container container) {
        final List<ItemStack> copies = new ArrayList<>(container.inventorySlots.size());
        for (Object slot : container.inventorySlots) {
            copies.add(StackJson.copy(((Slot) slot).getStack()));
        }
        return copies;
    }

    /**
     * Return one change for each slot whose stack differs from {@code before}: the container slot number, the owner,
     * the inventory class and index, and the stack before and after.
     *
     * <pre>
     *     {"containerSlot":3,"owner":"target","inventory":"InventoryPlayer","index":3,"before":{"key":"a@0","count":1}}
     * </pre>
     */
    public static JsonArray diffSlots(Container container, List<ItemStack> before, Owners owners) {
        final JsonArray changes = new JsonArray();
        for (int i = 0; i < container.inventorySlots.size() && i < before.size(); i++) {
            final Slot slot = (Slot) container.inventorySlots.get(i);
            final ItemStack was = before.get(i);
            final ItemStack now = slot.getStack();
            if (ItemStack.areItemStacksEqual(was, now)) {
                continue;
            }
            final JsonObject change = new JsonObject();
            change.addProperty("containerSlot", i);
            change.addProperty(
                "owner",
                slot.inventory == owners.player().inventory ? owners.playerOwner() : owners.otherOwner());
            change.addProperty(
                "inventory",
                slot.inventory.getClass()
                    .getSimpleName());
            change.addProperty("index", slot.getSlotIndex());
            change.add("before", StackJson.toJson(was));
            change.add("after", StackJson.toJson(now));
            changes.add(change);
        }
        return changes;
    }

    /** The owner of a change: {@code playerOwner} for a slot of {@code player}'s inventory, else {@code otherOwner}. */
    public record Owners(EntityPlayer player, String playerOwner, String otherOwner) {}
}
