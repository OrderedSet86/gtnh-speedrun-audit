package com.gtnhspeedrun.audit.compat;

import net.minecraft.block.Block;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

import appeng.tile.misc.TileInterface;

/**
 * Build the self-test's ME network: an ME Interface with one processing pattern, powered by a Creative Energy Cell
 * so the network provides the pattern to its crafting grid. The class references AE2 types, so use the class only
 * when AE2 is loaded.
 */
public final class Ae2Fixture {

    private Ae2Fixture() {}

    /**
     * Place the interface at {@code pos} and the cell one block east, at x + 1, and put the pattern in the interface.
     *
     * @return null on success, otherwise what went wrong
     */
    public static String build(WorldPos pos, ItemStack in, ItemStack out) {
        final World world = pos.world();
        final int x = pos.x();
        final int y = pos.y();
        final int z = pos.z();
        final Object rawInterface = Block.blockRegistry.getObject("appliedenergistics2:tile.BlockInterface");
        final Object rawCell = Block.blockRegistry.getObject("appliedenergistics2:tile.BlockCreativeEnergyCell");
        final Object rawPattern = Item.itemRegistry.getObject("appliedenergistics2:item.ItemEncodedPattern");
        if (!(rawInterface instanceof Block interfaceBlock && rawCell instanceof Block cellBlock
            && rawPattern instanceof Item patternItem)) {
            return "interface=" + rawInterface + " cell=" + rawCell + " pattern=" + rawPattern;
        }
        world.setBlock(x, y, z, interfaceBlock);
        world.setBlock(x + 1, y, z, cellBlock);
        final TileEntity te = world.getTileEntity(x, y, z);
        if (!(te instanceof TileInterface tile)) {
            return "no TileInterface at the interface, got " + te;
        }
        final ItemStack pattern = new ItemStack(patternItem);
        final NBTTagCompound tag = new NBTTagCompound();
        tag.setTag("in", wrapInTagList(in));
        tag.setTag("out", wrapInTagList(out));
        tag.setBoolean("crafting", false);
        tag.setBoolean("substitute", false);
        pattern.setTagCompound(tag);
        tile.getInterfaceDuality()
            .getPatterns()
            .setInventorySlotContents(0, pattern);
        return null;
    }

    private static NBTTagList wrapInTagList(ItemStack stack) {
        final NBTTagList tagList = new NBTTagList();
        tagList.appendTag(stack.writeToNBT(new NBTTagCompound()));
        return tagList;
    }
}
