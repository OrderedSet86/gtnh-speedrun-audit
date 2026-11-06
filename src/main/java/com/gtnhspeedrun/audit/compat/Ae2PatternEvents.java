package com.gtnhspeedrun.audit.compat;

import java.util.List;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;

import com.gtnhspeedrun.audit.trackers.PatternSinks;

import appeng.api.implementations.ICraftingPatternItem;

/** Find the pattern that a pattern terminal's encode wrote, for the AE2 pattern terminal mixins. */
public final class Ae2PatternEvents {

    private Ae2PatternEvents() {}

    /**
     * Log the encoded pattern: the first slot whose stack changed from {@code before} to an AE2 pattern. An encode that
     * wrote no pattern, such as one with an empty grid, logs nothing.
     */
    public static void logEncode(Container terminal, List<ItemStack> before, EntityPlayerMP player) {
        for (int i = 0; i < terminal.inventorySlots.size() && i < before.size(); i++) {
            final ItemStack now = ((Slot) terminal.inventorySlots.get(i)).getStack();
            if (now != null && now.getItem() instanceof ICraftingPatternItem
                && !ItemStack.areItemStacksEqual(before.get(i), now)) {
                PatternSinks.logPatternEncoded(
                    player,
                    now,
                    terminal.getClass()
                        .getSimpleName());
                return;
            }
        }
    }
}
