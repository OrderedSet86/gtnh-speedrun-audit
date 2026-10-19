package com.gtnhspeedrun.audit.snapshot;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.gtnhspeedrun.audit.core.JsonUtil;

/**
 * Encode item stacks for snapshots, trash records, NEI deletes and inventory-view clicks, in one JSON shape.
 *
 * <p>
 * A stack is {@code key} ({@link ItemKey#formatBaseKey}) and {@code count}. A stack with NBT also has {@code nbtHash},
 * 16 hex digits of the NBT hash, and {@code nbtB64}, the Base64 of the gzipped full NBT.
 */
public final class StackJson {

    private StackJson() {}

    public static JsonArray inventory(IInventory inventory) {
        final List<ItemStack> list = new ArrayList<>(inventory.getSizeInventory());
        for (int slot = 0; slot < inventory.getSizeInventory(); slot++) {
            list.add(inventory.getStackInSlot(slot));
        }
        return list(list);
    }

    /** Return the non-empty slots only, each with a {@code slot} index. */
    public static JsonArray list(List<ItemStack> stacks) {
        final JsonArray encoded = new JsonArray();
        for (int slot = 0; slot < stacks.size(); slot++) {
            final JsonObject stack = toJson(stacks.get(slot));
            if (stack == null) {
                continue;
            }
            final JsonObject withSlot = new JsonObject();
            withSlot.addProperty("slot", slot);
            for (Map.Entry<String, JsonElement> property : stack.entrySet()) {
                withSlot.add(property.getKey(), property.getValue());
            }
            encoded.add(withSlot);
        }
        return encoded;
    }

    /** Return null for an empty slot. */
    public static JsonObject toJson(ItemStack stack) {
        if (stack == null) {
            return null;
        }
        final JsonObject encoded = new JsonObject();
        encoded.addProperty("key", ItemKey.formatBaseKey(stack));
        encoded.addProperty("count", stack.stackSize);
        if (stack.hasTagCompound()) {
            final int HASH_HEX_DIGITS = 16;
            encoded.addProperty(
                "nbtHash",
                JsonUtil.canonicalNbtHash(stack.getTagCompound())
                    .substring(0, HASH_HEX_DIGITS));
            encoded.addProperty("nbtB64", JsonUtil.nbtToB64(stack.getTagCompound()));
        }
        return encoded;
    }

    /** Return a copy for a later diff. The game changes a slot's stack in place. */
    public static ItemStack copy(ItemStack stack) {
        return stack == null ? null : stack.copy();
    }
}
