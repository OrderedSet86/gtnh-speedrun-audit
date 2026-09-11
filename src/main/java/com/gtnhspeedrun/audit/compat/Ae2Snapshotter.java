package com.gtnhspeedrun.audit.compat;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import net.minecraft.item.Item;
import net.minecraft.server.MinecraftServer;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.gtnhspeedrun.audit.core.AuditLogger;
import com.gtnhspeedrun.audit.snapshot.KeyItemIndex;

import appeng.api.networking.storage.IStorageGrid;
import appeng.api.storage.data.IAEFluidStack;
import appeng.api.storage.data.IAEItemStack;
import appeng.hooks.TickHandler;
import appeng.me.Grid;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/**
 * Periodic per-network census of every loaded AE2 grid: counts only, taken in a single tick. Only
 * instantiated when AE2 is loaded.
 *
 * <p>
 * Cadence is coarser while the server is empty — item injection needs a player online; AFK automation drift
 * between censuses is expected and the bracketing censuses still bound it.
 *
 * <p>
 * getStorageList() is the network's cached aggregate (O(distinct types), no cell scans). Grid identity for
 * diffing across restarts is the persistent GridStorage id, reached through the one package-private accessor
 * ({@code Grid.getMyStorage()}) via a cached reflective call — Grid.getId() is random per instantiation.
 *
 * <p>
 * <b>Why the walk is not chunked.</b> Earlier versions spread it across ticks — first one grid per tick, then
 * a fixed number of item types per tick. Both were real machinery for a cost that does not need managing: at
 * the default hourly cadence a census is one tick in 72,000, and measured on the {@code Ae2Bench} harness a
 * full walk of a 100k-type network is ~12 ms of a 50 ms tick. One late tick an hour is not a perceptible
 * drag, and the amortized load is under 0.001% of a core.
 *
 * <p>
 * Taking it in one tick is also better evidence. A chunked census was a snapshot of a two-second window, so
 * an item moving mid-walk could be counted twice or missed once; an unchunked one is a snapshot of an
 * instant, and that whole class of discrepancy disappears.
 *
 * <p>
 * Because the census now runs entirely inside one server tick, nothing else mutates the grid while it reads,
 * so the live storage list can be iterated directly. Chunking is what forced the old code to copy every entry
 * into an array first, to avoid a ConcurrentModificationException across the tick boundary.
 */
public final class Ae2Snapshotter {

    private final AuditLogger logger;
    private final KeyItemIndex keyItems;
    private final MinecraftServer server;
    private final long activeIntervalTicks;
    private final long idleIntervalTicks;

    private final KeyCache keyCache = new KeyCache();
    private long tickCounter;
    private long lastCensusTick = Long.MIN_VALUE;
    private int censusIndex;
    private Method getMyStorage;
    private boolean reflectionFailed;

    public Ae2Snapshotter(AuditLogger logger, KeyItemIndex keyItems, MinecraftServer server, int activeMinutes,
        int idleMinutes) {
        this.logger = logger;
        this.keyItems = keyItems;
        this.server = server;
        this.activeIntervalTicks = activeMinutes * 60L * 20L;
        this.idleIntervalTicks = idleMinutes * 60L * 20L;
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        tickCounter++;
        final boolean anyoneOn = !server.getConfigurationManager().playerEntityList.isEmpty();
        final long interval = anyoneOn ? activeIntervalTicks : idleIntervalTicks;
        if (tickCounter - lastCensusTick >= interval) {
            requestCensus();
        }
    }

    /** Also wired to /audit snapshot. */
    public void requestCensus() {
        lastCensusTick = tickCounter;
        censusIndex++;
        // Copied out first. Not strictly required — nothing here adds or removes a network — but it is a
        // handful of entries, and it keeps the walk off AE2's own live collection.
        final List<Grid> grids = new ArrayList<>();
        try {
            for (Grid grid : TickHandler.INSTANCE.getGridList()) {
                grids.add(grid);
            }
        } catch (RuntimeException | LinkageError e) {
            // AE2 not ticking yet (or API drift): skip this census, try again next interval.
            return;
        }
        for (Grid grid : grids) {
            try {
                censusGrid(grid);
            } catch (RuntimeException | LinkageError e) {
                // One broken grid must not void the rest of the census.
            }
        }
    }

    /**
     * One grid's whole census, start to finish, inside the calling tick. Nothing else runs between the reads,
     * so the item and fluid lists are consistent with each other and with the node count.
     */
    private void censusGrid(Grid grid) {
        if (grid.isEmpty()) {
            return;
        }
        final IStorageGrid storage = grid.getCache(IStorageGrid.class);
        if (storage == null) {
            return;
        }
        // Iterated live: safe because the walk cannot span a tick boundary (see class javadoc).
        final ItemCensus items = walkItems(
            storage.getItemInventory()
                .getStorageList(),
            keyCache,
            keyItems);
        final FluidCensus fluids = walkFluids(
            storage.getFluidInventory()
                .getStorageList());
        emit(
            gridId(grid),
            grid.getNodes()
                .size(),
            censusIndex,
            items,
            fluids);
    }

    private void emit(String gridId, int nodeCount, int census, ItemCensus items, FluidCensus fluids) {
        final JsonObject ref = new JsonObject();
        ref.addProperty("gridId", gridId);
        ref.addProperty("census", census);
        ref.addProperty("nodes", nodeCount);
        ref.addProperty("distinctItems", items.size);
        ref.addProperty("totalItems", items.totalItems);
        ref.addProperty("distinctFluids", fluids.size);
        if (items.nbtVariantsCollapsed() > 0) {
            // Without per-item NBT hashing, NBT variants of one item+meta sum into a single key. Recorded so a
            // verifier can tell a collapsed census from one that genuinely had no variants.
            ref.addProperty("nbtVariantsCollapsed", items.nbtVariantsCollapsed());
        }

        final String fileName = "ae2-" + logger.clock()
            .get() + "-g" + gridId + ".json.gz";
        logger.logSnapshot("ae2_snapshot", fileName, () -> {
            final JsonObject snapshot = new JsonObject();
            snapshot.addProperty("gridId", gridId);
            snapshot.addProperty("census", census);
            snapshot.addProperty("nodes", nodeCount);
            final JsonArray itemArr = new JsonArray();
            for (int i = 0; i < items.size; i++) {
                final JsonObject o = new JsonObject();
                o.addProperty("key", items.keys[i]);
                o.addProperty("count", items.counts[i]);
                itemArr.add(o);
            }
            snapshot.add("items", itemArr);
            final JsonArray fluidArr = new JsonArray();
            for (int i = 0; i < fluids.size; i++) {
                final JsonObject o = new JsonObject();
                o.addProperty("fluid", fluids.names[i]);
                o.addProperty("mb", fluids.amounts[i]);
                fluidArr.add(o);
            }
            snapshot.add("fluids", fluidArr);
            return snapshot;
        }, ref);
    }

    /**
     * One census's item side. Holds only Strings and primitives — no live game state — which is what lets the
     * writer thread serialize it after the tick that built it has ended.
     *
     * <p>
     * Keys are {@code modid:name@meta}. NBT is deliberately not part of the key: the census is an accounting
     * of how much of what a network holds, and a tool's remaining durability or a suit's charge is not a
     * quantity anyone audits. Variants of one item+meta therefore sum into a single entry — totals are exact
     * either way, since merging only ever adds counts together. NBT-level evidence, where it is actually
     * wanted, lives in the inventory snapshots and {@code nbt_edit} lines.
     */
    public static final class ItemCensus {

        public String[] keys = new String[64];
        public long[] counts = new long[64];
        public int size;
        public long totalItems;

        private final KeyCache cache;
        private final KeyItemIndex keyItems;
        /** (item, meta) → output index, so NBT variants of one item find their existing entry. */
        private final IndexMap index = new IndexMap();
        private int seen;

        /** @param keyItems may be null (the bench has no watchlist to feed) */
        public ItemCensus(KeyCache cache, KeyItemIndex keyItems) {
            this.cache = cache;
            this.keyItems = keyItems;
        }

        /** Source entries that merged into an existing key because they differed only by NBT. */
        public int nbtVariantsCollapsed() {
            return seen - size;
        }

        public void add(IAEItemStack ae) {
            final Item item = ae.getItem();
            if (item == null) {
                return;
            }
            seen++;
            final long id = idKey(item, ae.getItemDamage());
            final long count = ae.getStackSize();
            totalItems += count;

            final int existing = index.get(id);
            if (existing >= 0) {
                counts[existing] += count;
                return;
            }

            String key = cache.get(id);
            if (key == null) {
                key = Item.itemRegistry.getNameForObject(item) + "@" + ae.getItemDamage();
                cache.put(id, key);
            }

            if (size == keys.length) {
                final int grown = size + (size >> 1) + 16;
                final String[] k = new String[grown];
                System.arraycopy(keys, 0, k, 0, size);
                keys = k;
                final long[] c = new long[grown];
                System.arraycopy(counts, 0, c, 0, size);
                counts = c;
            }
            index.put(id, size);
            keys[size] = key;
            counts[size] = count;
            size++;

            if (keyItems != null) {
                keyItems.checkBase(key, key, "ae2", null);
            }
        }
    }

    public static final class FluidCensus {

        public String[] names;
        public long[] amounts;
        public int size;
    }

    /**
     * Walk a whole list in one go. Both the live census and {@code Ae2Bench} go through here, so the bench
     * prices exactly the code the server runs.
     *
     * @param keyItems may be null (the bench has no watchlist to feed)
     */
    public static ItemCensus walkItems(Iterable<IAEItemStack> list, KeyCache cache, KeyItemIndex keyItems) {
        final ItemCensus out = new ItemCensus(cache, keyItems);
        for (IAEItemStack ae : list) {
            out.add(ae);
        }
        return out;
    }

    private static FluidCensus walkFluids(Iterable<IAEFluidStack> list) {
        final FluidCensus out = new FluidCensus();
        out.names = new String[16];
        out.amounts = new long[16];
        for (IAEFluidStack ae : list) {
            if (ae.getFluid() == null) {
                continue;
            }
            if (out.size == out.names.length) {
                final int grown = out.size * 2;
                final String[] n = new String[grown];
                System.arraycopy(out.names, 0, n, 0, out.size);
                out.names = n;
                final long[] a = new long[grown];
                System.arraycopy(out.amounts, 0, a, 0, out.size);
                out.amounts = a;
            }
            out.names[out.size] = ae.getFluid()
                .getName();
            out.amounts[out.size] = ae.getStackSize();
            out.size++;
        }
        return out;
    }

    private static long idKey(Item item, int meta) {
        return ((long) Item.getIdFromItem(item) << 32) | (meta & 0xFFFFFFFFL);
    }

    /**
     * Persistent (item id, meta) → key string cache. The same few tens of thousands of keys recur on every
     * census for the life of the server, so building them once turns the per-entry cost into one probe.
     * Open-addressed to keep the walk allocation-free; null slot means empty, so no key value is reserved.
     */
    public static final class KeyCache {

        private long[] ids = new long[1024];
        private String[] vals = new String[1024];
        private int size;

        public String get(long id) {
            int i = slot(id, ids.length);
            while (vals[i] != null) {
                if (ids[i] == id) {
                    return vals[i];
                }
                i = (i + 1) & (ids.length - 1);
            }
            return null;
        }

        public void put(long id, String val) {
            if ((size + 1) * 2 > ids.length) {
                grow();
            }
            int i = slot(id, ids.length);
            while (vals[i] != null) {
                if (ids[i] == id) {
                    vals[i] = val;
                    return;
                }
                i = (i + 1) & (ids.length - 1);
            }
            ids[i] = id;
            vals[i] = val;
            size++;
        }

        private void grow() {
            final long[] oldIds = ids;
            final String[] oldVals = vals;
            ids = new long[oldIds.length * 2];
            vals = new String[oldVals.length * 2];
            size = 0;
            for (int j = 0; j < oldVals.length; j++) {
                if (oldVals[j] != null) {
                    put(oldIds[j], oldVals[j]);
                }
            }
        }
    }

    /** Per-census (item id, meta) → output index, for merging NBT variants when hashing is off. */
    private static final class IndexMap {

        private long[] ids = new long[1024];
        private int[] vals = newFilled(1024);
        private int size;

        private static int[] newFilled(int n) {
            final int[] a = new int[n];
            java.util.Arrays.fill(a, -1);
            return a;
        }

        int get(long id) {
            int i = slot(id, ids.length);
            while (vals[i] >= 0) {
                if (ids[i] == id) {
                    return vals[i];
                }
                i = (i + 1) & (ids.length - 1);
            }
            return -1;
        }

        void put(long id, int val) {
            if ((size + 1) * 2 > ids.length) {
                grow();
            }
            int i = slot(id, ids.length);
            while (vals[i] >= 0) {
                if (ids[i] == id) {
                    vals[i] = val;
                    return;
                }
                i = (i + 1) & (ids.length - 1);
            }
            ids[i] = id;
            vals[i] = val;
            size++;
        }

        private void grow() {
            final long[] oldIds = ids;
            final int[] oldVals = vals;
            ids = new long[oldIds.length * 2];
            vals = newFilled(oldVals.length * 2);
            size = 0;
            for (int j = 0; j < oldVals.length; j++) {
                if (oldVals[j] >= 0) {
                    put(oldIds[j], oldVals[j]);
                }
            }
        }
    }

    /** Fibonacci-mixed slot: item ids are dense and low, which linear probing handles badly unmixed. */
    private static int slot(long id, int capacity) {
        return (int) ((id * 0x9E3779B97F4A7C15L) >>> 40) & (capacity - 1);
    }

    private String gridId(Grid grid) {
        if (!reflectionFailed && getMyStorage == null) {
            try {
                getMyStorage = Grid.class.getDeclaredMethod("getMyStorage");
                getMyStorage.setAccessible(true);
            } catch (ReflectiveOperationException | RuntimeException e) {
                reflectionFailed = true;
            }
        }
        if (!reflectionFailed) {
            try {
                final Object storage = getMyStorage.invoke(grid);
                if (storage instanceof appeng.me.GridStorage gs) {
                    return String.valueOf(gs.getID());
                }
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // Fall through to the ephemeral id.
            }
        }
        // Ephemeral fallback: stable within this session only; the census index still allows within-session diffs.
        return "ephemeral-" + System.identityHashCode(grid);
    }
}
