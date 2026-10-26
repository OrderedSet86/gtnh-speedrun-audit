package com.gtnhspeedrun.audit.harness;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompressedStreamTools;
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

    public static final int FLOOD_LIST_ELEMENTS = 2_000_000;
    public static final int FLOOD_BURST = 100;

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

    /**
     * Send forged /nbtedit replies as {@code player}, who has no edit open: one with a list of
     * {@link #FLOOD_LIST_ELEMENTS} empty elements, then {@link #FLOOD_BURST} small ones.
     *
     * @return the time the hook took for the large reply, in ms, or -1 without ServerUtilities
     */
    public static long sendForgedFlood(EntityPlayerMP player) throws IOException {
        if (!Compat.SERVER_UTILITIES.isLoaded()) {
            return -1;
        }
        final NBTTagCompound info = new NBTTagCompound();
        info.setString("type", "player");
        info.setString("id", player.getCommandSenderName());
        final long start = System.nanoTime();
        Holder.sendForged(player, info, readEmptyElementList(FLOOD_LIST_ELEMENTS));
        final long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        for (int packet = 0; packet < FLOOD_BURST; packet++) {
            Holder.sendForged(player, info, new NBTTagCompound());
        }
        return elapsedMs;
    }

    /** Read a compound with one list of {@code elements} empty elements, from 14 bytes of packet data at any count. */
    public static NBTTagCompound readEmptyElementList(int elements) throws IOException {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        final DataOutputStream out = new DataOutputStream(bytes);
        out.writeByte(10); // compound
        out.writeUTF("");
        out.writeByte(9); // list
        out.writeUTF("l");
        out.writeByte(0); // element type: end
        out.writeInt(elements);
        out.writeByte(0); // end of compound
        return CompressedStreamTools.read(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())));
    }

    private static final class Holder {

        private static void sendForged(EntityPlayerMP player, NBTTagCompound info, NBTTagCompound nbt) {
            // onMessage() calls EDITING.get(uuid).equals(info), which throws for a player with no open edit. Open a
            // different edit, so the forged reply matches none and changes nothing
            final NBTTagCompound otherEdit = new NBTTagCompound();
            otherEdit.setString("type", "other");
            CmdEditNBT.EDITING.put(
                player.getGameProfile()
                    .getId(),
                otherEdit);
            try {
                new MessageEditNBTResponse(info, nbt).onMessage(player);
            } finally {
                CmdEditNBT.EDITING.remove(
                    player.getGameProfile()
                        .getId());
            }
        }

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
