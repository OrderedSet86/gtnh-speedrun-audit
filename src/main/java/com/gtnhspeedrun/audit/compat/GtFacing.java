package com.gtnhspeedrun.audit.compat;

import net.minecraft.tileentity.TileEntity;

import org.jetbrains.annotations.Nullable;

import gregtech.api.interfaces.tileentity.IGregTechTileEntity;

/**
 * Read the front facing of a GT machine. The class references GT types, so call GtFacing only when GregTech is loaded.
 */
public final class GtFacing {

    private GtFacing() {}

    /** Return the front facing of a GT tile, such as NORTH, or null for any other tile. */
    @Nullable
    public static String readFacing(@Nullable TileEntity tile) {
        return tile instanceof IGregTechTileEntity machine ? machine.getFrontFacing()
            .name() : null;
    }
}
