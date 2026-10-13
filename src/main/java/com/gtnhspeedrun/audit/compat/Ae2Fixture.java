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
 * The self-test's ME network: an Interface holding one processing pattern, powered by a Creative Energy Cell so
 * the pattern is actually provided to the crafting grid. AE2-typed, so it lives here behind the Compat gate and
 * SelfTest stays loadable on a server without AE2.
 */
public final class Ae2Fixture {

    private Ae2Fixture() {}

    /** @return null on success, otherwise what went wrong */
    public static String build(World world, int x, int y, int z, ItemStack in, ItemStack out) {
        final Object iface = Block.blockRegistry.getObject("appliedenergistics2:tile.BlockInterface");
        final Object cell = Block.blockRegistry.getObject("appliedenergistics2:tile.BlockCreativeEnergyCell");
        final Object patternItem = Item.itemRegistry.getObject("appliedenergistics2:item.ItemEncodedPattern");
        if (!(iface instanceof Block ib) || !(cell instanceof Block cb) || !(patternItem instanceof Item pi)) {
            return "interface=" + iface + " cell=" + cell + " pattern=" + patternItem;
        }
        world.setBlock(x, y, z, ib);
        world.setBlock(x + 1, y, z, cb);
        final TileEntity te = world.getTileEntity(x, y, z);
        if (!(te instanceof TileInterface tile)) {
            return "no TileInterface at the interface, got " + te;
        }
        final ItemStack pattern = new ItemStack(pi);
        final NBTTagCompound tag = new NBTTagCompound();
        tag.setTag("in", list(in));
        tag.setTag("out", list(out));
        tag.setBoolean("crafting", false);
        tag.setBoolean("substitute", false);
        pattern.setTagCompound(tag);
        tile.getInterfaceDuality()
            .getPatterns()
            .setInventorySlotContents(0, pattern);
        return null;
    }

    private static NBTTagList list(ItemStack stack) {
        final NBTTagList l = new NBTTagList();
        l.appendTag(stack.writeToNBT(new NBTTagCompound()));
        return l;
    }
}
