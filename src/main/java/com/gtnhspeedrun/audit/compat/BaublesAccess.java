package com.gtnhspeedrun.audit.compat;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.inventory.IInventory;

/**
 * The baubles.api classes only exist when Baubles(-Expanded) is installed, so the touch of BaublesApi lives in
 * its own class behind the Compat gate — loading this class on a pack without Baubles would throw.
 */
public final class BaublesAccess {

    private BaublesAccess() {}

    public static IInventory get(EntityPlayerMP player) {
        if (!Compat.BAUBLES.isLoaded()) {
            return null;
        }
        try {
            return Holder.get(player);
        } catch (NoClassDefFoundError | RuntimeException e) {
            return null;
        }
    }

    private static final class Holder {

        static IInventory get(EntityPlayerMP player) {
            return baubles.api.BaublesApi.getBaubles(player);
        }
    }
}
