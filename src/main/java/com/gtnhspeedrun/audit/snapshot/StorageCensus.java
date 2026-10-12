package com.gtnhspeedrun.audit.snapshot;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraftforge.common.DimensionManager;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.gtnhspeedrun.audit.core.AuditLogger;
import com.gtnhspeedrun.audit.core.JsonUtil;

import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/**
 * Item storage that inventory snapshots don't reach: EnderStorage chests and pouches (one shared inventory per
 * frequency, so a pouch on the Moon empties into a chest at base — R1) and the Backpack mod's backpacks, whose
 * contents live in world files rather than in the backpack item. Forestry and Adventure backpacks keep their
 * contents in item NBT, which the inventory snapshots already carry.
 *
 * <p>
 * EnderStorage is read by reflection on its manager — the same private fields from 1.7.7 (pack 2.8.4) through
 * 1.8.7 — and never via saveToTag, which resizes inventories as a side effect. Storages loaded this session come
 * from the live inventories; the rest from the manager's saved tag. Backpack files are read on the writer thread
 * from disk, so they are as of the mod's last save of each backpack.
 */
public final class StorageCensus {

    private final AuditLogger logger;
    private final long intervalTicks;
    private long tickCounter;

    public StorageCensus(AuditLogger logger, int minutes) {
        this.logger = logger;
        this.intervalTicks = minutes * 60L * 20L;
    }

    public static boolean anythingToCensus() {
        return Loader.isModLoaded("EnderStorage") || Loader.isModLoaded("Backpack");
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        if (++tickCounter >= intervalTicks) {
            census("interval");
        }
    }

    /** Also wired to /audit snapshot. */
    public void census(String trigger) {
        tickCounter = 0;
        final JsonObject ref = new JsonObject();
        ref.addProperty("trigger", trigger);
        JsonArray ender = null;
        if (Loader.isModLoaded("EnderStorage")) {
            try {
                ender = enderStorage();
                ref.addProperty("enderStorages", ender.size());
            } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                ref.addProperty("enderStorageError", String.valueOf(e));
            }
        }
        final File backpackDir = Loader.isModLoaded("Backpack")
            && DimensionManager.getCurrentSaveRootDirectory() != null
                ? new File(DimensionManager.getCurrentSaveRootDirectory(), "backpacks/backpacks")
                : null;
        final JsonArray enderFinal = ender;
        logger.logSnapshot(
            "storage_census",
            String.format(
                "storage-%d.json.gz",
                logger.clock()
                    .get()),
            () -> {
                final JsonObject file = new JsonObject();
                if (enderFinal != null) {
                    file.add("enderStorage", enderFinal);
                }
                if (backpackDir != null) {
                    file.add("backpacks", backpacks(backpackDir));
                }
                return file;
            },
            ref);
    }

    /** Item storages only, keyed "freq|owner|item"; liquid tanks are skipped. Runs on the server thread. */
    private static JsonArray enderStorage() throws ReflectiveOperationException {
        final Class<?> managerClass = Class.forName("codechicken.enderstorage.api.EnderStorageManager");
        final Method instance = managerClass.getMethod("instance", boolean.class);
        final Object manager = instance.invoke(null, false);
        final Field mapField = managerClass.getDeclaredField("storageMap");
        final Field tagField = managerClass.getDeclaredField("saveTag");
        mapField.setAccessible(true);
        tagField.setAccessible(true);

        final Map<String, JsonArray> byKey = new TreeMap<>();
        final NBTTagCompound saved = (NBTTagCompound) tagField.get(manager);
        if (saved != null) {
            for (Object k : saved.func_150296_c()) {
                final String key = (String) k;
                if (key.endsWith("|item")) {
                    byKey.put(key, fromSavedTag(saved.getCompoundTag(key)));
                }
            }
        }
        final Map<?, ?> live = (Map<?, ?>) mapField.get(manager);
        final Map<Object, Object> copy;
        synchronized (live) {
            copy = new HashMap<>(live);
        }
        for (Map.Entry<Object, Object> e : copy.entrySet()) {
            final String key = String.valueOf(e.getKey());
            if (key.endsWith("|item") && e.getValue() instanceof IInventory inv) {
                byKey.put(key, StackJson.inventory(inv));
            }
        }

        final JsonArray out = new JsonArray();
        for (Map.Entry<String, JsonArray> e : byKey.entrySet()) {
            if (e.getValue()
                .size() == 0) {
                continue;
            }
            final String[] parts = e.getKey()
                .split("\\|", 3);
            final JsonObject o = new JsonObject();
            o.addProperty("freq", parts.length > 0 ? parts[0] : "?");
            o.addProperty("owner", parts.length > 1 ? parts[1] : "?");
            o.add("items", e.getValue());
            out.add(o);
        }
        return out;
    }

    private static JsonArray fromSavedTag(NBTTagCompound storage) {
        final NBTTagList items = storage.getTagList("Items", 10);
        final List<ItemStack> stacks = new ArrayList<>();
        for (int i = 0; i < items.tagCount(); i++) {
            final NBTTagCompound tag = items.getCompoundTagAt(i);
            final int slot = tag.getShort("Slot");
            while (stacks.size() <= slot) {
                stacks.add(null);
            }
            stacks.set(slot, ItemStack.loadItemStackFromNBT(tag));
        }
        return StackJson.list(stacks);
    }

    /** Raw file NBT per backpack; its layout is the Backpack mod's, decoded by the reader. Writer thread. */
    private static JsonArray backpacks(File dir) {
        final JsonArray out = new JsonArray();
        final File[] files = dir.listFiles((d, name) -> name.endsWith(".dat"));
        if (files == null) {
            return out;
        }
        java.util.Arrays.sort(files);
        for (File f : files) {
            final JsonObject o = new JsonObject();
            o.addProperty(
                "uuid",
                f.getName()
                    .substring(
                        0,
                        f.getName()
                            .length() - 4));
            o.addProperty("lastModified", f.lastModified());
            try (InputStream in = new FileInputStream(f)) {
                o.addProperty("nbtB64", JsonUtil.nbtToB64(CompressedStreamTools.readCompressed(in)));
            } catch (java.io.IOException | RuntimeException e) {
                o.addProperty("error", String.valueOf(e));
            }
            out.add(o);
        }
        return out;
    }
}
