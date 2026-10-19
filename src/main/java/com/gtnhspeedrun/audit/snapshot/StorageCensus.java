package com.gtnhspeedrun.audit.snapshot;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
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
 * Record the item storage that inventory snapshots don't reach, at a fixed interval.
 *
 * <p>
 * EnderStorage chests and pouches share one inventory per frequency, so a pouch on the Moon empties into a chest at
 * base. The Backpack mod saves a backpack's contents in world files, not in the backpack item. The contents of
 * Forestry and Adventure backpacks are in item NBT, so the inventory snapshots already have those contents.
 *
 * <p>
 * EnderStorage is read by reflection on EnderStorageManager. The private fields are the same from EnderStorage 1.7.7
 * (pack 2.8.4) through 1.8.7. The census never calls {@code saveToTag}, because {@code saveToTag} resizes
 * inventories. Storages loaded in this session come from the live inventories, and the others come from the
 * manager's saved tag. The writer thread reads the backpack files from disk, so each backpack is as of the Backpack
 * mod's last save of that backpack.
 */
public final class StorageCensus {

    private final AuditLogger logger;
    private final long intervalTicks;
    private long tickCounter;

    public StorageCensus(AuditLogger logger, int minutes) {
        this.logger = logger;
        final long TICKS_PER_MINUTE = 60L * 20L;
        this.intervalTicks = minutes * TICKS_PER_MINUTE;
    }

    public static boolean hasAnythingToCensus() {
        return Loader.isModLoaded("EnderStorage") || Loader.isModLoaded("Backpack");
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        if (++tickCounter >= intervalTicks) {
            takeCensus("interval");
        }
    }

    /** Write a {@code storage_census} snapshot file, and restart the interval. */
    public void takeCensus(String trigger) {
        tickCounter = 0;
        final JsonObject reference = new JsonObject();
        reference.addProperty("trigger", trigger);
        JsonArray ender = null;
        if (Loader.isModLoaded("EnderStorage")) {
            try {
                ender = readEnderStorage();
                reference.addProperty("enderStorages", ender.size());
            } catch (ReflectiveOperationException | RuntimeException | LinkageError e) { // noqa: NAR142, reflection
                                                                                         // into EnderStorage
                reference.addProperty("enderStorageError", String.valueOf(e));
            }
        }
        final Path backpackDir = Loader.isModLoaded("Backpack") ? resolveBackpackDir() : null;
        final JsonArray enderFinal = ender;
        logger.logSnapshot(
            new AuditLogger.Snapshot(
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
                        file.add("backpacks", readBackpacks(backpackDir));
                    }
                    return file;
                },
                reference));
    }

    /**
     * Return each non-empty item storage, sorted by its {@code freq|owner|item} key. Liquid tanks are skipped.
     * Call on the server thread.
     */
    private static JsonArray readEnderStorage() throws ReflectiveOperationException {
        final Class<?> managerClass = Class.forName("codechicken.enderstorage.api.EnderStorageManager");
        final Method instanceMethod = managerClass.getMethod("instance", boolean.class);
        final Object manager = instanceMethod.invoke(null, false);
        final Field mapField = managerClass.getDeclaredField("storageMap");
        final Field tagField = managerClass.getDeclaredField("saveTag");
        mapField.setAccessible(true);
        tagField.setAccessible(true);

        final Map<String, JsonArray> byKey = new TreeMap<>();
        final NBTTagCompound saved = (NBTTagCompound) tagField.get(manager);
        if (saved != null) {
            for (Object rawKey : saved.func_150296_c()) {
                final String key = (String) rawKey;
                if (key.endsWith("|item")) {
                    byKey.put(key, decodeSavedStorage(saved.getCompoundTag(key)));
                }
            }
        }
        final Map<?, ?> live = (Map<?, ?>) mapField.get(manager);
        final Map<Object, Object> liveCopy;
        synchronized (live) {
            liveCopy = new HashMap<>(live);
        }
        for (Map.Entry<Object, Object> entry : liveCopy.entrySet()) {
            final String key = String.valueOf(entry.getKey());
            if (key.endsWith("|item") && entry.getValue() instanceof IInventory inventory) {
                byKey.put(key, StackJson.inventory(inventory));
            }
        }

        final JsonArray out = new JsonArray();
        for (Map.Entry<String, JsonArray> entry : byKey.entrySet()) {
            if (entry.getValue()
                .size() == 0) {
                continue;
            }
            final String[] parts = entry.getKey()
                .split("\\|", 3);
            final JsonObject storage = new JsonObject();
            storage.addProperty("freq", parts.length > 0 ? parts[0] : "?");
            storage.addProperty("owner", parts.length > 1 ? parts[1] : "?");
            storage.add("items", entry.getValue());
            out.add(storage);
        }
        return out;
    }

    private static JsonArray decodeSavedStorage(NBTTagCompound storage) {
        final int TAG_COMPOUND = 10;
        final NBTTagList items = storage.getTagList("Items", TAG_COMPOUND);
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

    private static Path resolveBackpackDir() {
        final File saveRoot = DimensionManager.getCurrentSaveRootDirectory();
        return saveRoot == null ? null
            : saveRoot.toPath()
                .resolve("backpacks/backpacks");
    }

    /** Return each backpack file's raw NBT. The layout is the Backpack mod's. Call on the writer thread. */
    private static JsonArray readBackpacks(Path backpackDir) {
        final String BACKPACK_SUFFIX = ".dat";
        final JsonArray out = new JsonArray();
        final List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> listing = Files.newDirectoryStream(
            backpackDir,
            path -> path.getFileName()
                .toString()
                .endsWith(BACKPACK_SUFFIX))) {
            for (Path file : listing) {
                files.add(file);
            }
        } catch (IOException | DirectoryIteratorException e) {
            return out;
        }
        Collections.sort(files);
        for (Path file : files) {
            final String fileName = file.getFileName()
                .toString();
            final JsonObject backpack = new JsonObject();
            backpack.addProperty("uuid", fileName.substring(0, fileName.length() - BACKPACK_SUFFIX.length()));
            backpack.addProperty("lastModified", readLastModifiedMillis(file));
            try (InputStream in = Files.newInputStream(file)) {
                backpack.addProperty("nbtB64", JsonUtil.nbtToB64(CompressedStreamTools.readCompressed(in)));
            } catch (IOException | RuntimeException e) { // noqa: NAR142, NBT read of Backpack mod files
                backpack.addProperty("error", String.valueOf(e));
            }
            out.add(backpack);
        }
        return out;
    }

    private static long readLastModifiedMillis(Path file) {
        try {
            return Files.getLastModifiedTime(file)
                .toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }
}
