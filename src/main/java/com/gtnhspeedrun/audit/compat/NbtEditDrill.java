package com.gtnhspeedrun.audit.compat;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntityChest;
import net.minecraft.world.World;

import com.gtnhspeedrun.audit.core.JsonUtil;

/**
 * Self-test drill for the /nbtedit mixin: a real ServerUtilities edit, applied through the same packet handler
 * the editor's save button reaches. serverutils classes only exist on a pack server, so the touch lives in its
 * own class behind the Compat gate, like BaublesAccess.
 */
public final class NbtEditDrill {

    private NbtEditDrill() {}

    /**
     * Puts one diamond in a chest at (x, y, z), then edits the chest to hold two. Returns the canonical hashes the
     * nbt_edit line must carry: { before, after }. Null when ServerUtilities is absent.
     */
    public static String[] editChest(World world, EntityPlayerMP player, int x, int y, int z) {
        if (!Compat.SERVER_UTILITIES.isLoaded()) {
            return null;
        }
        world.setBlock(x, y, z, Blocks.chest);
        final TileEntityChest chest = (TileEntityChest) world.getTileEntity(x, y, z);
        chest.setInventorySlotContents(0, new ItemStack(Items.diamond, 1));
        final NBTTagCompound before = new NBTTagCompound();
        chest.writeToNBT(before);

        final NBTTagCompound after = (NBTTagCompound) before.copy();
        after.getTagList("Items", 10)
            .getCompoundTagAt(0)
            .setByte("Count", (byte) 2);
        final NBTTagCompound info = new NBTTagCompound();
        info.setString("type", "block");
        info.setInteger("x", x);
        info.setInteger("y", y);
        info.setInteger("z", z);
        info.setString("id", before.getString("id"));
        Holder.apply(player, info, after);
        return new String[] { JsonUtil.canonicalNbtHash(before), JsonUtil.canonicalNbtHash(after) };
    }

    private static final class Holder {

        static void apply(EntityPlayerMP player, NBTTagCompound info, NBTTagCompound nbt) {
            // onMessage only applies an edit the player has open, as /nbtedit block registers it
            serverutils.command.CmdEditNBT.EDITING.put(
                player.getGameProfile()
                    .getId(),
                info);
            new serverutils.net.MessageEditNBTResponse(info, nbt).onMessage(player);
        }
    }
}
