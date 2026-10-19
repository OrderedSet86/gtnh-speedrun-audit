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

    private ItemKey() {}

    /** Return the key with no NBT hash. The {@code keyItems} config entries have this form, or no {@code @meta}. */
    public static String formatBaseKey(ItemStack stack) {
        final String name = String.valueOf(Item.itemRegistry.getNameForObject(stack.getItem()));
        return name + "@" + stack.getItemDamage();
    }
}
