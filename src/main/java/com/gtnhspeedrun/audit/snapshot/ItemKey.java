package com.gtnhspeedrun.audit.snapshot;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

import com.gtnhspeedrun.audit.core.JsonUtil;

/**
 * Canonical item identity for snapshots and milestones: {@code modid:name@meta}, plus {@code #hash16} when the
 * stack carries NBT. GT meta-items make the meta half load-bearing — item id alone collapses thousands of
 * distinct materials.
 */
public final class ItemKey {

    private ItemKey() {}

    public static String of(ItemStack stack) {
        final String base = base(stack);
        if (!stack.hasTagCompound()) {
            return base;
        }
        return base + "#" + JsonUtil.canonicalNbtHash(stack.getTagCompound()).substring(0, 16);
    }

    /** Key without the NBT discriminator — what the config's keyItems list matches against. */
    public static String base(ItemStack stack) {
        final String name = String.valueOf(Item.itemRegistry.getNameForObject(stack.getItem()));
        return name + "@" + stack.getItemDamage();
    }
}
