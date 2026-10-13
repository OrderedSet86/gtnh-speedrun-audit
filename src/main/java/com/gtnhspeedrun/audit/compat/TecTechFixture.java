package com.gtnhspeedrun.audit.compat;

import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import tectech.thing.casing.TTCasingsContainer;

/**
 * The self-test's TecTech multiblock: an Active Transformer, built in place by StructureLib's creative construct
 * (which runs no structure check) with one casing swapped for a TecTech EV 4A multi-amp energy hatch. It can only
 * form through TTMultiblockBase's own onPostTick check, about 100 ticks after the controller is placed, and its
 * only hatch sits in TecTech's own eEnergyMulti list, so EV at 4 amps in the log shows that list was read.
 * GT-typed, so it lives here behind the Compat gate and SelfTest stays loadable without GT.
 */
public final class TecTechFixture {

    /** GT MetaTileEntity id of the Active Transformer controller. */
    private static final int ACTIVE_TRANSFORMER_META = 15300;
    /** GT MetaTileEntity id of TecTech's EV 4A multi-amp energy hatch. */
    private static final int ENERGY_HATCH_EV_4A_META = 15109;

    private TecTechFixture() {}

    /** @return null on success, otherwise what went wrong */
    public static String build(World world, EntityPlayer player, int x, int y, int z) {
        final Object block = Block.blockRegistry.getObject("gregtech:gt.blockmachines");
        if (!(block instanceof Block machines)) {
            return "gregtech:gt.blockmachines not registered";
        }
        final ItemStack controller = new ItemStack(machines, 1, ACTIVE_TRANSFORMER_META);
        if (!place(world, player, controller, x, y, z)) {
            return "controller not placed";
        }
        final TileEntity te = world.getTileEntity(x, y, z);
        if (!(te instanceof IGregTechTileEntity base) || base.getMetaTileEntity() == null) {
            return "no GT tile entity at the controller, got " + te;
        }
        final Object multi = base.getMetaTileEntity();
        try {
            // IConstructable is StructureLib's, which is not on the compile classpath
            multi.getClass()
                .getMethod("construct", ItemStack.class, boolean.class)
                .invoke(multi, controller, false);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return "construct failed: " + e;
        }
        for (int dx = -2; dx <= 2; dx++) {
            for (int dy = -2; dy <= 2; dy++) {
                for (int dz = -2; dz <= 2; dz++) {
                    if (world.getBlock(x + dx, y + dy, z + dz) != TTCasingsContainer.sBlockCasingsTT) {
                        continue;
                    }
                    final ItemStack hatch = new ItemStack(machines, 1, ENERGY_HATCH_EV_4A_META);
                    final boolean placed = place(world, player, hatch, x + dx, y + dy, z + dz);
                    return placed ? null : "energy hatch not placed";
                }
            }
        }
        return "construct placed no TecTech casing";
    }

    private static boolean place(World world, EntityPlayer player, ItemStack stack, int x, int y, int z) {
        if (world.getBlock(x, y, z) != Blocks.air) {
            world.setBlockToAir(x, y, z);
        }
        return ((ItemBlock) stack.getItem())
            .placeBlockAt(stack, player, world, x, y, z, 1, 0.5f, 0.5f, 0.5f, stack.getItemDamage());
    }
}
