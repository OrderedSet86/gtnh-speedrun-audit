package com.gtnhspeedrun.audit.compat;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.inventory.IInventory;

import baubles.api.BaublesApi;

/**
 * Read a player's Baubles inventory. The baubles.api classes exist only when Baubles or Baubles Expanded is
 * installed, so every reference to {@code BaublesApi} is in the nested {@code Holder} class, behind the
 * {@code Compat.BAUBLES} guard. A call into {@code Holder} on a pack without Baubles throws
 * {@code NoClassDefFoundError}.
 */
public final class BaublesAccess {

    private BaublesAccess() {}

    /** Return the player's Baubles inventory, or null without Baubles or when the Baubles API doesn't match. */
    public static IInventory readBaubles(EntityPlayerMP player) {
        if (!Compat.BAUBLES.isLoaded()) {
            return null;
        }
        try {
            return Holder.readBaubles(player);
        } catch (NoClassDefFoundError | RuntimeException e) { // noqa: NAR142, degrade and report over Baubles code
            return null;
        }
    }

    private static final class Holder {

        private static IInventory readBaubles(EntityPlayerMP player) {
            return BaublesApi.getBaubles(player);
        }
    }
}
