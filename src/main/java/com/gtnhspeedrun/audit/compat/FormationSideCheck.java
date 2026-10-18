package com.gtnhspeedrun.audit.compat;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

import com.gtnhspeedrun.audit.trackers.GtFormationLogger;
import com.gtnhspeedrun.audit.trackers.StructureTiers;

import gregtech.api.GregTechAPI;
import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.metatileentity.implementations.MTEMultiBlockBase;

/**
 * The self-test's check that formation logging survives the side it runs on. v0.8.0 crashed dedicated servers when
 * an Extreme Entity Crusher formed: it declares a field typed by the client-only kubatech.client.effect.EntityRenderer,
 * so listing its fields throws there. The self-test runs on a dedicated server, so reading every registered
 * multiblock here covers every controller class in the pack. GT-typed, so it lives here behind the Compat gate and
 * SelfTest stays loadable without GT.
 */
public final class FormationSideCheck {

    private static final String EXTREME_ENTITY_CRUSHER = "MTEExtremeEntityCrusher";

    private FormationSideCheck() {}

    /** Reads the structure tiers of every registered multiblock prototype; one entry per controller that threw. */
    public static List<String> readEveryMultiblock() {
        final List<String> failed = new ArrayList<>();
        for (IMetaTileEntity mte : GregTechAPI.METATILEENTITIES) {
            if (!(mte instanceof MTEMultiBlockBase)) {
                continue;
            }
            try {
                StructureTiers.read(mte);
            } catch (Throwable t) {
                failed.add(
                    mte.getClass()
                        .getSimpleName() + ": "
                        + t);
            }
        }
        return failed;
    }

    /** Multiblock classes whose fields cannot be listed on this side, so StructureTiers skips their own fields. */
    public static Set<String> unlistableMultiblocks() {
        final Set<String> unlistable = new TreeSet<>();
        for (IMetaTileEntity mte : GregTechAPI.METATILEENTITIES) {
            if (!(mte instanceof MTEMultiBlockBase)) {
                continue;
            }
            for (Class<?> c = mte.getClass(); c != null; c = c.getSuperclass()) {
                try {
                    c.getDeclaredFields();
                } catch (LinkageError e) {
                    unlistable.add(c.getSimpleName());
                }
            }
        }
        return unlistable;
    }

    /**
     * Places an Extreme Entity Crusher controller and logs its formation the way the checkStructure hook does,
     * outside the hook's guard, so a failure reaches the self-test rather than the error log.
     *
     * @return null on success, otherwise what went wrong
     */
    public static String logCrusherFormation(World world, EntityPlayer player, int x, int y, int z) {
        int meta = -1;
        for (int i = 0; i < GregTechAPI.METATILEENTITIES.length; i++) {
            final IMetaTileEntity mte = GregTechAPI.METATILEENTITIES[i];
            if (mte != null && EXTREME_ENTITY_CRUSHER.equals(
                mte.getClass()
                    .getSimpleName())) {
                meta = i;
                break;
            }
        }
        if (meta < 0) {
            return EXTREME_ENTITY_CRUSHER + " not registered";
        }
        final Object block = Block.blockRegistry.getObject("gregtech:gt.blockmachines");
        if (!(block instanceof Block machines)) {
            return "gregtech:gt.blockmachines not registered";
        }
        if (!TecTechFixture.place(world, player, new ItemStack(machines, 1, meta), x, y, z)) {
            return "controller not placed";
        }
        final TileEntity te = world.getTileEntity(x, y, z);
        if (!(te instanceof IGregTechTileEntity base)
            || !(base.getMetaTileEntity() instanceof GtFormationLogger logger)) {
            return "no GT multiblock at the controller, got " + te;
        }
        try {
            logger.audit$logFormation();
        } catch (Throwable t) {
            return "audit$logFormation threw " + t;
        }
        return null;
    }
}
