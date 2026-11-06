package com.gtnhspeedrun.audit.harness;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.InventoryBasic;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;

import com.gtnhspeedrun.audit.compat.Compat;

import codechicken.lib.inventory.SlotDummy;
import codechicken.lib.packet.PacketCustom;
import codechicken.nei.NEISPH;
import codechicken.nei.NEIServerConfig;
import codechicken.nei.NEIServerUtils;

/**
 * Drive the NEI cheats that the NEI mixins log, as a fake player, for the self-test. NEI checks a permission for most
 * packets, and a fake player has none, so the drill calls the NEI methods behind those packets directly. The spawner
 * and ghost-slot packets have no permission check, so the drill builds those packets and passes them to
 * {@code NEISPH.handlePacket}. The codechicken classes exist only when NEI is installed, so every reference to them is
 * in the nested classes. The nested classes load behind the {@code Compat.NEI} guard.
 */
public final class NeiDrill {

    public static final String SPAWNER_MOB = "Pig";
    public static final String DISABLED_ACTION = "rain";
    // packet types of NEI's PacketIDs.C2S, the same in NEI 2.8.19 through 2.8.160
    private static final int SEND_MOB_SPAWNER_ID = 15;
    private static final int SET_DUMMY_SLOT = 25;

    private NeiDrill() {}

    /**
     * Run each NEI cheat once as {@code player}: rain toggled twice, magnet mode toggled twice, a creative+ row cycle
     * of 0 steps, the creative+ inventory closed, the "rain" lock cleared, the mob of a spawner at {@code spawner} set
     * to {@link #SPAWNER_MOB}, and an apple written into a ghost slot. The rain and magnet toggles run twice, so both
     * end as they began.
     *
     * @return whether the drill ran: false without NEI
     */
    public static boolean runCheats(EntityPlayerMP player, WorldPos spawner) {
        if (!Compat.NEI.isLoaded()) {
            return false;
        }
        Holder.runCheats(player, spawner);
        return true;
    }

    private static final class Holder {

        /** Run the cheats listed on {@link NeiDrill#runCheats}, in that order. */
        private static void runCheats(EntityPlayerMP player, WorldPos spawner) {
            // NEI loads a player's save on login, and a fake player never logs in
            if (NEIServerConfig.forPlayer(player.getCommandSenderName()) == null) {
                NEIServerConfig.loadPlayer(player);
            }
            final World world = spawner.world();
            NEIServerUtils.toggleRaining(world, false);
            NEIServerUtils.toggleRaining(world, false);
            runSending(() -> NEIServerUtils.toggleMagnetMode(player));
            runSending(() -> NEIServerUtils.toggleMagnetMode(player));
            NEIServerUtils.cycleCreativeInv(player, 0);
            runSending(() -> NEISPH.processCreativeInv(player, false));
            runSending(() -> NEIServerConfig.disableAction(world.provider.dimensionId, DISABLED_ACTION, false));

            world.setBlock(spawner.x(), spawner.y(), spawner.z(), Blocks.mob_spawner);
            final PacketCustom spawnerPacket = new PacketCustom("NEI", SEND_MOB_SPAWNER_ID);
            spawnerPacket.writeCoord(spawner.x(), spawner.y(), spawner.z());
            spawnerPacket.writeString(SPAWNER_MOB);
            handleAsServer(spawnerPacket, player);

            final Container ghostContainer = new GhostSlotContainer();
            player.openContainer = ghostContainer;
            try {
                final PacketCustom slotPacket = new PacketCustom("NEI", SET_DUMMY_SLOT);
                slotPacket.writeShort(0);
                slotPacket.writeItemStack(new ItemStack(Items.apple), true);
                handleAsServer(slotPacket, player);
            } finally {
                player.openContainer = player.inventoryContainer;
            }
        }

        /**
         * Run {@code neiCall}, a call of an NEI method that writes a packet to the player. A fake player has no netty
         * channel, so FML's send throws after the HEAD injections ran.
         */
        private static void runSending(Runnable neiCall) {
            try {
                neiCall.run();
            } catch (RuntimeException ignored) { // noqa: NAR142, degrade over FML's send to a fake player
            }
        }

        /** Hand {@code outgoing} to NEI's server packet handler, as the server reads the packet off the wire. */
        private static void handleAsServer(PacketCustom outgoing, EntityPlayerMP player) {
            final PacketCustom incoming = new PacketCustom(
                outgoing.getByteBuf()
                    .copy());
            new NEISPH().handlePacket(incoming, player, player.playerNetServerHandler);
        }
    }

    /** A container with one ghost slot, the kind of slot that the filter of an Item Translocator has. */
    private static final class GhostSlotContainer extends Container {

        private GhostSlotContainer() {
            addSlotToContainer(new SlotDummy(new InventoryBasic("ghost", false, 1), 0, 0, 0));
        }

        @Override
        public boolean canInteractWith(EntityPlayer player) {
            return true;
        }
    }
}
