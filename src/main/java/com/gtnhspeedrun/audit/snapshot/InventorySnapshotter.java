package com.gtnhspeedrun.audit.snapshot;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.event.entity.living.LivingDeathEvent;

import org.apache.commons.codec.binary.Base64;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.gtnhspeedrun.audit.compat.BaublesAccess;
import com.gtnhspeedrun.audit.core.AuditLogger;
import com.gtnhspeedrun.audit.core.JsonUtil;
import com.gtnhspeedrun.audit.trackers.AuditSinks;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;

/**
 * Full-NBT player inventory snapshots: main, armor, baubles, ender chest. Captured on the server thread as
 * plain strings/bytes (small — a player is ~80 stacks), then assembled and gzipped on the writer thread.
 *
 * <p>
 * Event-triggered captures are where the verification value is: join/leave bracket every play segment, death
 * fires BEFORE the drops, and the gamemode tracker calls in on every transition.
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
            final String killer = AuditSinks.entityName(event.source.getEntity());
            if (killer != null) {
                data.addProperty("killer", killer);
            }
            final String direct = AuditSinks.entityName(event.source.getSourceOfDamage());
            if (direct != null && !direct.equals(killer)) {
                data.addProperty("directKiller", direct);
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

    public void capture(EntityPlayerMP player, String trigger) {
        final JsonObject sections = new JsonObject();
        sections.add("main", captureSlots(player.inventory.mainInventory));
        sections.add("armor", captureSlots(player.inventory.armorInventory));
        sections.add("enderChest", captureInventory(player.getInventoryEnderChest()));
        final IInventory baubles = BaublesAccess.get(player);
        if (baubles != null) {
            sections.add("baubles", captureInventory(baubles));
        }

        checkKeyItems(player);

        final String uuid = player.getGameProfile()
            .getId()
            .toString();
        final JsonObject snapshot = new JsonObject();
        snapshot.addProperty("uuid", uuid);
        snapshot.addProperty("name", player.getCommandSenderName());
        snapshot.addProperty("trigger", trigger);
        snapshot.add("sections", sections);

        final JsonObject ref = new JsonObject();
        ref.addProperty("uuid", uuid);
        ref.addProperty("name", player.getCommandSenderName());
        ref.addProperty("trigger", trigger);
        final String fileName = "inv-" + logger.clock()
            .get() + "-" + uuid.substring(0, 8) + "-" + trigger + ".json.gz";
        logger.logSnapshot("inv_snapshot", fileName, () -> snapshot, ref);
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
        for (ItemStack s : slots) {
            list.add(s);
        }
        return captureList(list);
    }

    private static JsonArray captureInventory(IInventory inv) {
        final List<ItemStack> list = new ArrayList<>(inv.getSizeInventory());
        for (int i = 0; i < inv.getSizeInventory(); i++) {
            list.add(inv.getStackInSlot(i));
        }
        return captureList(list);
    }

    private static JsonArray captureList(List<ItemStack> stacks) {
        final JsonArray arr = new JsonArray();
        for (int slot = 0; slot < stacks.size(); slot++) {
            final ItemStack stack = stacks.get(slot);
            if (stack == null) {
                continue;
            }
            final JsonObject o = new JsonObject();
            o.addProperty("slot", slot);
            o.addProperty("key", ItemKey.base(stack));
            o.addProperty("count", stack.stackSize);
            if (stack.hasTagCompound()) {
                o.addProperty(
                    "nbtHash",
                    JsonUtil.canonicalNbtHash(stack.getTagCompound())
                        .substring(0, 16));
                o.addProperty("nbtB64", nbtB64(stack.getTagCompound()));
            }
            arr.add(o);
        }
        return arr;
    }

    private static String nbtB64(NBTTagCompound tag) {
        try {
            return Base64.encodeBase64String(CompressedStreamTools.compress(tag));
        } catch (Exception e) {
            return "ERROR:" + e;
        }
    }
}
