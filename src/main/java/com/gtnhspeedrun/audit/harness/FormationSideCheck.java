package com.gtnhspeedrun.audit.harness;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;

import com.gtnhspeedrun.audit.trackers.GtFormationLogger;
import com.gtnhspeedrun.audit.trackers.StructureTiers;

import gregtech.api.GregTechAPI;
import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.metatileentity.implementations.MTEMultiBlockBase;

/**
 * Check that formation logging works on a dedicated server, for the self-test. The Extreme Entity Crusher class has
 * a field typed by the client-only {@code kubatech.client.effect.EntityRenderer}, so listing the fields of that
 * class throws on a dedicated server. The self-test runs on a dedicated server, so reading every registered
 * multiblock there tests every controller class in the pack. The class references GT types, so use the class only
 * when GT is loaded.
 */
public final class FormationSideCheck {

    private static final String EXTREME_ENTITY_CRUSHER = "MTEExtremeEntityCrusher";

    private FormationSideCheck() {}

    /**
     * Read the structure tiers of every registered multiblock prototype.
     *
     * @return one entry for each controller whose read threw
     */
    public static List<String> readEveryMultiblock() {
        final List<String> failed = new ArrayList<>();
        for (IMetaTileEntity mte : GregTechAPI.METATILEENTITIES) {
            if (!(mte instanceof MTEMultiBlockBase)) {
                continue;
            }
            try {
                StructureTiers.read(mte);
            } catch (RuntimeException | LinkageError e) { // noqa: NAR142, degrade and report over GT machine code
                failed.add(
                    mte.getClass()
                        .getSimpleName() + ": "
                        + e);
            }
        }
        return failed;
    }

    /**
     * Return the multiblock classes and superclasses whose fields can't be listed on this side.
     * {@code StructureTiers} reads no field of such a class.
     */
    public static Set<String> findUnlistableMultiblocks() {
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
     * Place an Extreme Entity Crusher controller at {@code controllerPos} as {@code player}, and call
     * {@code audit$logFormation()} on the controller, as the {@code checkStructure} hook does. The call here has no
     * guard of the hook, so a failure returns to the self-test and the error log gets nothing.
     *
     * @return null on success, otherwise what went wrong
     */
    public static String logCrusherFormation(EntityPlayer player, WorldPos controllerPos) {
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
        final Object rawMachines = Block.blockRegistry.getObject("gregtech:gt.blockmachines");
        if (!(rawMachines instanceof Block machines)) {
            return "gregtech:gt.blockmachines not registered";
        }
        if (!TecTechFixture.place(player, new ItemStack(machines, 1, meta), controllerPos)) {
            return "controller not placed";
        }
        final TileEntity te = controllerPos.world()
            .getTileEntity(controllerPos.x(), controllerPos.y(), controllerPos.z());
        if (!(te instanceof IGregTechTileEntity base && base.getMetaTileEntity() instanceof GtFormationLogger logger)) {
            return "no GT multiblock at the controller, got " + te;
        }
        try {
            logger.audit$logFormation();
        } catch (RuntimeException | LinkageError e) { // noqa: NAR142, degrade and report over GT machine code
            return "audit$logFormation threw " + e;
        }
        return null;
    }
}
