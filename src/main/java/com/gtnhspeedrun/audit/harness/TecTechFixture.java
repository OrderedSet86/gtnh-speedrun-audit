package com.gtnhspeedrun.audit.harness;

import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

import com.gtnewhorizon.structurelib.alignment.IAlignment;
import com.gtnewhorizon.structurelib.alignment.enumerable.ExtendedFacing;
import com.gtnewhorizon.structurelib.alignment.enumerable.Rotation;

import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.metatileentity.implementations.MTEMultiBlockBase;
import tectech.thing.casing.TTCasingsContainer;

/**
 * Build and check the self-test's TecTech multiblock, an Active Transformer. The StructureLib creative construct
 * builds the multiblock in place and runs no structure check. Then one casing is swapped for a TecTech EV 4A
 * multi-amp energy hatch.
 *
 * <p>
 * The multiblock can form only through the {@code onPostTick} structure check of {@code TTMultiblockBase}, about
 * 100 ticks after the controller is placed. The multiblock's only hatch is in the TecTech {@code eEnergyMulti}
 * list, so EV at 4 amps in the log shows that the audit read that list. The class references GT types, so use the
 * class only when GT is loaded.
 */
public final class TecTechFixture {

    private static final int ACTIVE_TRANSFORMER_MTE_ID = 15300;
    private static final int EV_4A_ENERGY_HATCH_MTE_ID = 15109;

    /**
     * Where {@link #build} placed the energy hatch, for the self-test's footprint check. Null until {@link #build}
     * picks the casing for the hatch.
     */
    public static int[] hatchPosition;

    private TecTechFixture() {}

    /**
     * Place the controller at {@code controllerPos} as {@code player}, and build the multiblock around the controller,
     * with the energy hatch in one casing.
     *
     * @return null on success, otherwise what went wrong
     */
    public static String build(EntityPlayer player, WorldPos controllerPos) {
        final Object rawMachines = Block.blockRegistry.getObject("gregtech:gt.blockmachines");
        if (!(rawMachines instanceof Block machines)) {
            return "gregtech:gt.blockmachines not registered";
        }
        final World world = controllerPos.world();
        final ItemStack controller = new ItemStack(machines, 1, ACTIVE_TRANSFORMER_MTE_ID);
        if (!place(player, controller, controllerPos)) {
            return "controller not placed";
        }
        final TileEntity te = world.getTileEntity(controllerPos.x(), controllerPos.y(), controllerPos.z());
        if (!(te instanceof IGregTechTileEntity base) || base.getMetaTileEntity() == null) {
            return "no GT tile entity at the controller, got " + te;
        }
        final Object multi = base.getMetaTileEntity();
        try {
            multi.getClass()
                .getMethod("construct", ItemStack.class, boolean.class)
                .invoke(multi, controller, false);
        } catch (ReflectiveOperationException | RuntimeException e) { // noqa: NAR142, degrade and report over
                                                                      // reflection into GT code
            return "construct failed: " + e;
        }
        final int SEARCH_RADIUS = 2;
        for (int dx = -SEARCH_RADIUS; dx <= SEARCH_RADIUS; dx++) {
            for (int dy = -SEARCH_RADIUS; dy <= SEARCH_RADIUS; dy++) {
                for (int dz = -SEARCH_RADIUS; dz <= SEARCH_RADIUS; dz++) {
                    final WorldPos casingPos = controllerPos.offset(dx, dy, dz);
                    if (world.getBlock(casingPos.x(), casingPos.y(), casingPos.z())
                        != TTCasingsContainer.sBlockCasingsTT) {
                        continue;
                    }
                    final ItemStack hatch = new ItemStack(machines, 1, EV_4A_ENERGY_HATCH_MTE_ID);
                    final boolean placed = place(player, hatch, casingPos);
                    hatchPosition = new int[] { casingPos.x(), casingPos.y(), casingPos.z() };
                    return placed ? null : "energy hatch not placed";
                }
            }
        }
        return "construct placed no TecTech casing";
    }

    /** Clear the block at {@code pos}, then place the block of {@code stack} there as {@code player}, from above. */
    public static boolean place(EntityPlayer player, ItemStack stack, WorldPos pos) {
        final int TOP_SIDE = 1;
        final float BLOCK_CENTRE = 0.5f;
        final World world = pos.world();
        if (world.getBlock(pos.x(), pos.y(), pos.z()) != Blocks.air) {
            world.setBlockToAir(pos.x(), pos.y(), pos.z());
        }
        return ((ItemBlock) stack.getItem()).placeBlockAt(
            stack,
            player,
            world,
            pos.x(),
            pos.y(),
            pos.z(),
            TOP_SIDE,
            BLOCK_CENTRE,
            BLOCK_CENTRE,
            BLOCK_CENTRE,
            stack.getItemDamage());
    }

    /**
     * @return {direction, rotation, flip} of the controller at {@code pos}, or null when the controller has no
     *         StructureLib alignment
     */
    public static String[] readFacing(WorldPos pos) {
        final TileEntity te = readTile(pos);
        if (!(te instanceof IGregTechTileEntity base && base.getMetaTileEntity() instanceof IAlignment alignment)) {
            return null;
        }
        final ExtendedFacing facing = alignment.getExtendedFacing();
        return new String[] { facing.getDirection()
            .name(),
            facing.getRotation()
                .name(),
            facing.getFlip()
                .name() };
    }

    /**
     * Run {@code checkStructure(true)} on the formed controller at {@code pos}. Nothing moved, so the re-check must
     * log no {@code multiblock_footprint} line.
     *
     * @return null on success, otherwise what went wrong
     */
    public static String recheck(WorldPos pos) {
        final TileEntity te = readTile(pos);
        if (!(te instanceof IGregTechTileEntity base && base.getMetaTileEntity() instanceof MTEMultiBlockBase multi)) {
            return "no GT multiblock at the controller, got " + te;
        }
        if (!multi.mMachine) {
            return "not formed before the re-check";
        }
        return multi.checkStructure(true, base) ? null : "re-check unformed it";
    }

    /**
     * Set the rotation of the formed controller at {@code pos} to clockwise, and re-check the controller. The Active
     * Transformer is a cube that accepts a hatch in any casing, so the multiblock re-forms with a new footprint key.
     *
     * @return null on success, otherwise what went wrong
     */
    public static String rotateAndRecheck(WorldPos pos) {
        final TileEntity te = readTile(pos);
        if (!(te instanceof IGregTechTileEntity base && base.getMetaTileEntity() instanceof MTEMultiBlockBase multi
            && multi instanceof IAlignment alignment)) {
            return "no StructureLib GT multiblock at the controller, got " + te;
        }
        final ExtendedFacing before = alignment.getExtendedFacing();
        alignment.setExtendedFacing(ExtendedFacing.of(before.getDirection(), Rotation.CLOCKWISE, before.getFlip()));
        if (multi.mMachine) {
            return "still formed after the rotation";
        }
        return multi.checkStructure(true, base) ? null : "did not re-form after the rotation";
    }

    private static TileEntity readTile(WorldPos pos) {
        return pos.world()
            .getTileEntity(pos.x(), pos.y(), pos.z());
    }
}
