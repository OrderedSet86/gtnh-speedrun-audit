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
 * ServerUtilities' /trash_can opens a plain chest GUI over a throwaway 36-slot InventoryBasic. Nothing voids the
 * items explicitly: whatever is in it when the GUI closes is garbage-collected with it. So the record is the
 * inventory's contents at the moment the player's open container stops being that chest — on the next player
 * tick, or at logout if they disconnect with it open.
 *
 * <p>
 * The chest is recognised the way the command builds it: an InventoryBasic with a custom name equal to the
 * server-side translation of {@code key.serverutilities.trash}. No ServerUtilities types are touched.
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
            record(player, was, "closed");
        }
        if (now instanceof ContainerChest chest && isTrash(chest.getLowerChestInventory())) {
            open.put(id, chest);
        }
    }

    @SubscribeEvent
    public void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        final ContainerChest was = open.remove(event.player.getUniqueID());
        if (was != null) {
            record(event.player, was, "logout");
        }
    }

    private void record(EntityPlayer player, ContainerChest chest, String how) {
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

    static boolean isTrash(IInventory inv) {
        return inv instanceof InventoryBasic && inv.hasCustomInventoryName()
            && inv.getSizeInventory() == TRASH_SLOTS
            && StatCollector.translateToLocal("key.serverutilities.trash")
                .equals(inv.getInventoryName());
    }
}
