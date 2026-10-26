package com.gtnhspeedrun.audit.snapshot;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

/**
 * Build the item key of snapshots and milestones: {@code modid:name@meta}.
 *
 * <p>
 * GT meta-items put thousands of materials under one item id, so the key includes the meta.
 */
public final class ItemKey {

    public static final String UNKNOWN_ITEM = "unknown";

    private ItemKey() {}

    /**
     * Return the key with no NBT hash. The {@code keyItems} config entries have this form, or no {@code @meta}. The key
     * of a stack with no item is {@link #UNKNOWN_ITEM}.
     */
    public static String formatBaseKey(ItemStack stack) {
        // a stack read from a packet with an unregistered item id has no item, and Forge's getItemDamage() calls the
        // item
        if (stack.getItem() == null) {
            return UNKNOWN_ITEM;
        }
        final String name = String.valueOf(Item.itemRegistry.getNameForObject(stack.getItem()));
        return name + "@" + stack.getItemDamage();
    }
}
