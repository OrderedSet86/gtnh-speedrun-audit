package com.gtnhspeedrun.audit.snapshot;

import java.util.List;

import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.gtnhspeedrun.audit.core.JsonUtil;

/**
 * The one stack shape every record uses — snapshots, trash, NEI deletes, inventory-view clicks: {@code key}
 * ({@link ItemKey#base}), {@code count}, and for NBT-bearing stacks {@code nbtHash} (16 hex) plus the full
 * compressed {@code nbtB64}. A reader decoding one decodes all of them.
 */
public final class StackJson {

    private StackJson() {}

    /** Null for an empty slot. */
    public static JsonObject of(ItemStack stack) {
        if (stack == null) {
            return null;
        }
        final JsonObject o = new JsonObject();
        o.addProperty("key", ItemKey.base(stack));
        o.addProperty("count", stack.stackSize);
        if (stack.hasTagCompound()) {
            o.addProperty(
                "nbtHash",
                JsonUtil.canonicalNbtHash(stack.getTagCompound())
                    .substring(0, 16));
            o.addProperty("nbtB64", JsonUtil.nbtToB64(stack.getTagCompound()));
        }
        return o;
    }

    /** Non-empty slots only, each tagged with its index. */
    public static JsonArray list(List<ItemStack> stacks) {
        final JsonArray arr = new JsonArray();
        for (int slot = 0; slot < stacks.size(); slot++) {
            final JsonObject o = of(stacks.get(slot));
            if (o == null) {
                continue;
            }
            final JsonObject withSlot = new JsonObject();
            withSlot.addProperty("slot", slot);
            for (java.util.Map.Entry<String, com.google.gson.JsonElement> e : o.entrySet()) {
                withSlot.add(e.getKey(), e.getValue());
            }
            arr.add(withSlot);
        }
        return arr;
    }

    public static JsonArray inventory(IInventory inv) {
        final List<ItemStack> list = new java.util.ArrayList<>(inv.getSizeInventory());
        for (int i = 0; i < inv.getSizeInventory(); i++) {
            list.add(inv.getStackInSlot(i));
        }
        return list(list);
    }

    /** Detached copy, so a later read sees the stack as it was, not as the game mutated it since. */
    public static ItemStack copy(ItemStack stack) {
        return stack == null ? null : stack.copy();
    }
}
