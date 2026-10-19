package com.gtnhspeedrun.audit.trackers;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.ContainerChest;
import net.minecraft.inventory.IInventory;
import net.minecraft.inventory.InventoryBasic;
import net.minecraft.util.StatCollector;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.gtnhspeedrun.audit.core.AuditLogger;
import com.gtnhspeedrun.audit.snapshot.StackJson;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/**
 * Log the items that a player leaves in a ServerUtilities /trash_can chest.
 *
 * <p>
 * /trash_can opens a plain chest GUI over a throwaway 36-slot {@code InventoryBasic}. ServerUtilities doesn't
 * delete the items. The items in the inventory are garbage-collected with the inventory after the GUI closes. So
 * the logged items are the inventory's contents when the player's open container stops being that chest, in the
 * next player tick, or at logout when the player disconnects with the chest open.
 *
 * <p>
 * The chest is recognised by how the command builds the chest: a 36-slot {@code InventoryBasic} whose custom name
 * equals the server-side translation of {@code key.serverutilities.trash}. The class uses no ServerUtilities types.
 */
public final class TrashCanCommandTracker {

    private static final int TRASH_SLOTS = 36;

    private final AuditLogger logger;
    private final Map<UUID, ContainerChest> open = new HashMap<>();

    public TrashCanCommandTracker(AuditLogger logger) {
        this.logger = logger;
    }

    @SubscribeEvent
    public void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !(event.player instanceof EntityPlayerMP)) {
            return;
        }
        final EntityPlayer player = event.player;
        final UUID id = player.getUniqueID();
        final Container now = player.openContainer;
        final ContainerChest was = open.get(id);
        if (was == now) {
            return;
        }
        if (was != null) {
            open.remove(id);
            logTrash(player, was, "closed");
        }
        if (now instanceof ContainerChest chest && isTrash(chest.getLowerChestInventory())) {
            open.put(id, chest);
        }
    }

    @SubscribeEvent
    public void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        final ContainerChest was = open.remove(event.player.getUniqueID());
        if (was != null) {
            logTrash(event.player, was, "logout");
        }
    }

    private void logTrash(EntityPlayer player, ContainerChest chest, String how) {
        final JsonArray items = StackJson.inventory(chest.getLowerChestInventory());
        if (items.size() == 0) {
            return;
        }
        final JsonObject data = new JsonObject();
        data.addProperty(
            "uuid",
            player.getUniqueID()
                .toString());
        data.addProperty("name", player.getCommandSenderName());
        data.addProperty("dim", player.dimension);
        data.addProperty("how", how);
        data.add("items", items);
        logger.log("trash_can_closed", data);
    }

    private static boolean isTrash(IInventory inventory) {
        return inventory instanceof InventoryBasic && inventory.hasCustomInventoryName()
            && inventory.getSizeInventory() == TRASH_SLOTS
            && StatCollector.translateToLocal("key.serverutilities.trash")
                .equals(inventory.getInventoryName());
    }
}
