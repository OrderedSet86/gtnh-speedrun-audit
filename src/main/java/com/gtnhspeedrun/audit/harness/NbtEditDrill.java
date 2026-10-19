package com.gtnhspeedrun.audit.harness;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntityChest;
import net.minecraft.world.World;

import com.gtnhspeedrun.audit.compat.Compat;
import com.gtnhspeedrun.audit.core.JsonUtil;

import serverutils.command.CmdEditNBT;
import serverutils.net.MessageEditNBTResponse;

/**
 * Drive a real ServerUtilities /nbtedit edit for the self-test of the /nbtedit mixin, through the packet handler
 * that the editor's save button reaches. The serverutils classes exist only when ServerUtilities is installed, so
 * every reference to them is in the nested {@code Holder} class, behind the {@code Compat.SERVER_UTILITIES} guard.
 */
public final class NbtEditDrill {

    private NbtEditDrill() {}

    /**
     * Put one diamond in a chest at {@code pos}, then edit the chest's stack to two diamonds as {@code player}.
     *
     * @return the canonical NBT hashes {before, after} that the self-test expects in the {@code nbt_edit} line, or
     *         null without ServerUtilities
     */
    public static String[] editChest(EntityPlayerMP player, WorldPos pos) {
        if (!Compat.SERVER_UTILITIES.isLoaded()) {
            return null;
        }
        final World world = pos.world();
        final int x = pos.x();
        final int y = pos.y();
        final int z = pos.z();
        world.setBlock(x, y, z, Blocks.chest);
        final TileEntityChest chest = (TileEntityChest) world.getTileEntity(x, y, z);
        chest.setInventorySlotContents(0, new ItemStack(Items.diamond, 1));
        final NBTTagCompound before = new NBTTagCompound();
        chest.writeToNBT(before);

        final NBTTagCompound after = (NBTTagCompound) before.copy();
        final int TAG_COMPOUND = 10;
        after.getTagList("Items", TAG_COMPOUND)
            .getCompoundTagAt(0)
            .setByte("Count", (byte) 2);
        final NBTTagCompound info = new NBTTagCompound();
        info.setString("type", "block");
        info.setInteger("x", x);
        info.setInteger("y", y);
        info.setInteger("z", z);
        info.setString("id", before.getString("id"));
        Holder.applyEdit(player, info, after);
        return new String[] { JsonUtil.canonicalNbtHash(before), JsonUtil.canonicalNbtHash(after) };
    }

    private static final class Holder {

        private static void applyEdit(EntityPlayerMP player, NBTTagCompound info, NBTTagCompound nbt) {
            // onMessage() applies only an edit that the player has open. Open the edit the way /nbtedit block does
            CmdEditNBT.EDITING.put(
                player.getGameProfile()
                    .getId(),
                info);
            new MessageEditNBTResponse(info, nbt).onMessage(player);
        }
    }
}
