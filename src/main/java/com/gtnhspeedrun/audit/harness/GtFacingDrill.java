package com.gtnhspeedrun.audit.harness;

import net.minecraft.tileentity.TileEntity;
import net.minecraftforge.common.util.ForgeDirection;

import org.jetbrains.annotations.Nullable;

import gregtech.api.interfaces.tileentity.IGregTechTileEntity;

/**
 * Turn a placed GT machine to a new front facing, the way a wrench does, so the facing mixin logs a
 * {@code machine_facing} line. The class references GT types, so use the class only when GT is loaded.
 */
public final class GtFacingDrill {

    private GtFacingDrill() {}

    /**
     * Turn the GT machine at {@code spot} to the facing opposite the machine's facing. Return the facings as
     * {@code from} and {@code to}, or null when a GT machine isn't at {@code spot}.
     */
    @Nullable
    public static String[] turnMachine(WorldPos spot) {
        final TileEntity tile = spot.world()
            .getTileEntity(spot.x(), spot.y(), spot.z());
        if (!(tile instanceof IGregTechTileEntity machine)) {
            return null;
        }
        final ForgeDirection from = machine.getFrontFacing();
        machine.setFrontFacing(from.getOpposite());
        return new String[] { from.name(), machine.getFrontFacing()
            .name() };
    }
}
