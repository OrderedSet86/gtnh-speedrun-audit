package com.gtnhspeedrun.audit.snapshot;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;
import net.minecraftforge.event.entity.living.LivingDeathEvent;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.gtnhspeedrun.audit.compat.BaublesAccess;
import com.gtnhspeedrun.audit.core.AuditLogger;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;

/**
 * Write full-NBT snapshots of a player's main inventory, armor, baubles and ender chest.
 *
 * <p>
 * The stacks are encoded on the server thread. A player has about 80 stacks. The writer thread gzips the snapshot
 * file. A snapshot is taken on join, on leave, on death and on each {@link #capture} call. Forge fires
 * {@code LivingDeathEvent} before the drops spawn, so the death snapshot has the items the player died with.
 */
public final class InventorySnapshotter {

    private final AuditLogger logger;
    private final KeyItemIndex keyItems;

    public InventorySnapshotter(AuditLogger logger, KeyItemIndex keyItems) {
        this.logger = logger;
        this.keyItems = keyItems;
    }

    // ------------------------------------------------------------------ triggers

    @SubscribeEvent
    public void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.player instanceof EntityPlayerMP player) {
            capture(player, "join");
        }
    }

    @SubscribeEvent
    public void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.player instanceof EntityPlayerMP player) {
            capture(player, "leave");
        }
    }

    /** Log a {@code death} event with the killer and the death message, then take the death snapshot. */
    @SubscribeEvent
    public void onDeath(LivingDeathEvent event) {
        if (event.entityLiving instanceof EntityPlayerMP player) {
            final JsonObject data = new JsonObject();
            data.addProperty(
                "uuid",
                player.getGameProfile()
                    .getId()
                    .toString());
            data.addProperty("name", player.getCommandSenderName());
            data.addProperty("damageSource", event.source.damageType);
            final String killer = describeEntity(event.source.getEntity());
            if (killer != null) {
                data.addProperty("killer", killer);
            }
            final String directKiller = describeEntity(event.source.getSourceOfDamage());
            if (directKiller != null && !directKiller.equals(killer)) {
                data.addProperty("directKiller", directKiller);
            }
            data.addProperty(
                "message",
                event.source.func_151519_b(player)
                    .getUnformattedText());
            data.addProperty("dim", player.dimension);
            logger.log("death", data);
            capture(player, "death");
        }
    }

    // ------------------------------------------------------------------ capture

    /** Snapshot the player's inventories, and check the main inventory against the key-item watchlist. */
    public void capture(EntityPlayerMP player, String trigger) {
        final JsonObject sections = new JsonObject();
        sections.add("main", captureSlots(player.inventory.mainInventory));
        sections.add("armor", captureSlots(player.inventory.armorInventory));
        sections.add("enderChest", StackJson.inventory(player.getInventoryEnderChest()));
        final IInventory baubles = BaublesAccess.readBaubles(player);
        if (baubles != null) {
            sections.add("baubles", StackJson.inventory(baubles));
        }

        checkKeyItems(player);

        final String uuid = player.getGameProfile()
            .getId()
            .toString();
        final JsonObject snapshot = new JsonObject();
        snapshot.addProperty("uuid", uuid);
        snapshot.addProperty("name", player.getCommandSenderName());
        snapshot.addProperty("trigger", trigger);
        PlayerPlace.addPlace(snapshot, player);
        snapshot.add("sections", sections);

        final JsonObject reference = new JsonObject();
        reference.addProperty("uuid", uuid);
        reference.addProperty("name", player.getCommandSenderName());
        reference.addProperty("trigger", trigger);
        PlayerPlace.addPlace(reference, player);
        final int UUID_PREFIX_LENGTH = 8;
        final String fileStem = "inv-" + logger.clock()
            .get() + "-" + uuid.substring(0, UUID_PREFIX_LENGTH) + "-" + trigger;
        logger.logSnapshot(new AuditLogger.Snapshot("inv_snapshot", fileStem, () -> snapshot, reference));
    }

    private void checkKeyItems(EntityPlayerMP player) {
        if (keyItems.isEmpty()) {
            return;
        }
        final String uuid = player.getGameProfile()
            .getId()
            .toString();
        for (ItemStack stack : player.inventory.mainInventory) {
            keyItems.checkStack(stack, "inventory", uuid);
        }
    }

    private static JsonArray captureSlots(ItemStack[] slots) {
        final List<ItemStack> list = new ArrayList<>(slots.length);
        for (ItemStack stack : slots) {
            list.add(stack);
        }
        return StackJson.list(list);
    }

    /**
     * Return {@code player:<name>} for a player, the registered entity name such as {@code Zombie} for another
     * entity, or null for no entity.
     */
    private static String describeEntity(Entity entity) {
        if (entity == null) {
            return null;
        }
        if (entity instanceof EntityPlayer player) {
            return "player:" + player.getCommandSenderName();
        }
        final String registered = EntityList.getEntityString(entity);
        return registered != null ? registered
            : entity.getClass()
                .getSimpleName();
    }
}
