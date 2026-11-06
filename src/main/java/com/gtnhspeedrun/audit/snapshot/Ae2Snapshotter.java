package com.gtnhspeedrun.audit.snapshot;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

import net.minecraft.item.Item;
import net.minecraft.server.MinecraftServer;

import org.jetbrains.annotations.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.gtnhspeedrun.audit.AuditConfig;
import com.gtnhspeedrun.audit.core.AuditLogger;
import com.gtnhspeedrun.audit.snapshot.Ae2PatternCensus.Place;

import appeng.api.networking.IGridHost;
import appeng.api.networking.IGridNode;
import appeng.api.networking.storage.IStorageGrid;
import appeng.api.storage.data.IAEFluidStack;
import appeng.api.storage.data.IAEItemStack;
import appeng.api.util.DimensionalCoord;
import appeng.hooks.TickHandler;
import appeng.me.Grid;
import appeng.me.GridStorage;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/**
 * Take a periodic census of every loaded AE2 grid, as counts only, within one server tick. The class references AE2
 * types, so construct it only when AE2 is loaded.
 *
 * <p>
 * The interval is longer while the server is empty, because a player must be online to put items into a network.
 * AFK automation still changes the counts between censuses, and the censuses before and after bound that drift.
 *
 * <p>
 * A census is one {@code ae2_census} line and one snapshot file, with an entry in the file's {@code grids} for each
 * grid with an item, a fluid or a pattern. {@code gridsSkipped} on the line is the count of the other grids.
 *
 * <p>
 * {@code getStorageList()} returns the network's cached totals. A walk takes one step for each distinct type, and reads
 * no storage cell. The grid id is the {@code GridStorage} id, read through the package-private
 * {@code Grid.getMyStorage()} by a cached reflective call. {@code Grid.getId()} is random for each instance. AE2 saves
 * the {@code GridStorage} id in each node's NBT, and {@code Grid.add()} gives a grid the saved storage of a joining
 * node, or a new storage when no node has a saved storage. Newly placed nodes have no saved storage. The {@code anchor}
 * of each entry is a block position. A re-formed grid has the same anchor while the grid's ME Controller is in place.
 *
 * <p>
 * The walk runs in one tick. At the default hourly interval a census is one tick in 72,000. On the {@code Ae2Bench}
 * harness a full walk of a network with 100k types took about 12 ms of the 50 ms tick, and the amortized load is
 * under 0.001% of a core. The alternative, a census spread across ticks, would count an item twice or miss the item
 * when the item moves mid-walk. That census would also have to copy every entry to avoid a
 * {@code ConcurrentModificationException}. No other code runs on the server thread during the walk, so the walk
 * iterates the live storage list.
 */
public final class Ae2Snapshotter {

    /**
     * The first census is one interval into the session. With {@code Long.MIN_VALUE} here,
     * {@code tickCounter - lastCensusTick} would overflow to a negative number, and no automatic census would run.
     */
    public static final long INITIAL_LAST_CENSUS_TICK = 0;

    private final AuditLogger logger;
    private final KeyItemIndex keyItems;
    private final MinecraftServer server;
    private final long activeIntervalTicks;
    private final long idleIntervalTicks;

    private final KeyCache keyCache = new KeyCache();
    private long tickCounter;
    private long lastCensusTick = INITIAL_LAST_CENSUS_TICK;
    private int censusIndex;
    private Method getMyStorage;
    private boolean reflectionFailed;

    /**
     * Read the census interval in minutes while a player is online from {@link AuditConfig#ae2SnapshotActiveMinutes},
     * and while the server is empty from {@link AuditConfig#ae2SnapshotIdleMinutes}.
     */
    public Ae2Snapshotter(AuditLogger logger, KeyItemIndex keyItems, MinecraftServer server) {
        this.logger = logger;
        this.keyItems = keyItems;
        this.server = server;
        this.activeIntervalTicks = AuditConfig.ae2SnapshotActiveMinutes * 60L * 20L;
        this.idleIntervalTicks = AuditConfig.ae2SnapshotIdleMinutes * 60L * 20L;
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        tickCounter++;
        final boolean anyoneOn = !server.getConfigurationManager().playerEntityList.isEmpty();
        final long interval = anyoneOn ? activeIntervalTicks : idleIntervalTicks;
        if (isCensusDue(tickCounter, lastCensusTick, interval)) {
            requestCensus();
        }
    }

    public static boolean isCensusDue(long tickCounter, long lastCensusTick, long interval) {
        return tickCounter - lastCensusTick >= interval;
    }

    /**
     * Take a census of every loaded grid now, and restart the interval. A silent failure would leave the run with no
     * AE2 evidence, unnoticed until a verifier opens the export. The failures are therefore collected and announced
     * to every player.
     */
    public void requestCensus() {
        lastCensusTick = tickCounter;
        censusIndex++;
        final List<String> failures = new ArrayList<>();
        final List<Grid> grids = new ArrayList<>();
        try {
            for (Grid grid : TickHandler.INSTANCE.getGridList()) {
                grids.add(grid);
            }
        } catch (RuntimeException | LinkageError e) { // noqa: NAR142, degrade and report over AE2 grid code
            // the AE2 tick handler has no grid list yet, or the AE2 API changed. No grid can be read, so the whole
            // census fails
            failures.add(
                Ae2CensusFailures
                    .logAe2CensusFailure(new Ae2CensusFailures.Ae2CensusFailure(censusIndex, "grid list", null, e)));
            Ae2CensusFailures.announceAe2CensusFailure(failures);
            return;
        }
        final List<GridCensus> written = new ArrayList<>();
        int gridsSkipped = 0;
        for (Grid grid : grids) {
            String id = null;
            try {
                id = readGridId(grid);
                final GridCensus census = takeGridCensus(grid, id);
                if (census == null) {
                    gridsSkipped++;
                } else {
                    written.add(census);
                }
            } catch (RuntimeException | LinkageError e) { // noqa: NAR142, degrade and report over AE2 grid code
                // one broken grid must not stop the census of the other grids
                failures.add(
                    Ae2CensusFailures
                        .logAe2CensusFailure(new Ae2CensusFailures.Ae2CensusFailure(censusIndex, "grid", id, e)));
            }
        }
        emit(censusIndex, List.copyOf(written), gridsSkipped);
        if (!failures.isEmpty()) {
            Ae2CensusFailures.announceAe2CensusFailure(failures);
        }
    }

    /**
     * Return the grid's persistent {@code GridStorage} id. When the reflection fails, return an ephemeral id from the
     * grid's identity hash.
     */
    private String readGridId(Grid grid) {
        if (!reflectionFailed && getMyStorage == null) {
            try {
                getMyStorage = Grid.class.getDeclaredMethod("getMyStorage");
                getMyStorage.setAccessible(true);
            } catch (ReflectiveOperationException | RuntimeException e) { // noqa: NAR142, degrade and report over
                                                                          // reflection into AE2 code
                reflectionFailed = true;
            }
        }
        if (!reflectionFailed) {
            try {
                final Object storage = getMyStorage.invoke(grid);
                if (storage instanceof GridStorage gridStorage) {
                    return String.valueOf(gridStorage.getID());
                }
            } catch (ReflectiveOperationException | RuntimeException ignored) { // noqa: NAR142, degrade and report over
                                                                                // reflection into AE2 code
                // fall through to the ephemeral id
            }
        }
        // The ephemeral id lasts as long as the Grid object, and never past the session. While the Grid object lives,
        // two censuses can match the grid by this id.
        return "ephemeral-" + System.identityHashCode(grid);
    }

    /**
     * Take one grid's census within the calling tick. No other code runs between the reads, so the item list, the fluid
     * list and the node count are from one instant. Return null for a grid with no item, no fluid and no pattern.
     */
    @Nullable
    private GridCensus takeGridCensus(Grid grid, String gridId) {
        if (grid.isEmpty()) {
            return null;
        }
        final IStorageGrid storage = grid.getCache(IStorageGrid.class);
        if (storage == null) {
            return null;
        }
        final ItemCensus items = walkItems(
            storage.getItemInventory()
                .getStorageList(),
            keyCache,
            keyItems);
        final FluidCensus fluids = walkFluids(
            storage.getFluidInventory()
                .getStorageList());
        final Ae2PatternCensus patterns = Ae2PatternCensus.takePatternCensus(grid, keyCache);
        if (!(items.size > 0 || fluids.size > 0 || patterns.countPatternStacks() > 0)) {
            return null;
        }
        return new GridCensus(
            gridId,
            readAnchor(grid),
            grid.getNodes()
                .size(),
            items,
            fluids,
            patterns);
    }

    /**
     * Return the place of the grid's ME Controller, or the place of the grid's lowest node when the grid has no
     * controller. Places sort by (dim, x, y, z). For a controller of several blocks, return the lowest block. Return
     * null when no node has a place.
     */
    @Nullable
    private static Place readAnchor(Grid grid) {
        // TileController's supertypes include IC2 interfaces that are not on the compile classpath, so the class is
        // matched by name
        final String CONTROLLER_CLASS = "appeng.tile.networking.TileController";
        for (Class<? extends IGridHost> machineClass : grid.getMachinesClasses()) {
            if (CONTROLLER_CLASS.equals(machineClass.getName())) {
                final Place controller = findLowestPlace(grid.getMachines(machineClass));
                if (controller != null) {
                    return controller;
                }
            }
        }
        return findLowestPlace(grid.getNodes());
    }

    private static final Comparator<Place> PLACE_ORDER = Comparator.comparingInt(Place::dim)
        .thenComparingInt(Place::x)
        .thenComparingInt(Place::y)
        .thenComparingInt(Place::z);

    @Nullable
    private static Place findLowestPlace(Iterable<IGridNode> nodes) {
        Place lowest = null;
        for (IGridNode node : nodes) {
            final DimensionalCoord location = node.getGridBlock()
                .getLocation();
            if (location == null) {
                continue;
            }
            final Place place = new Place(location.getDimension(), location.x, location.y, location.z);
            if (lowest == null || PLACE_ORDER.compare(place, lowest) < 0) {
                lowest = place;
            }
        }
        return lowest;
    }

    /**
     * Walk a whole item list in one pass. {@code Ae2Bench} times this method, so the live census must call this method
     * and no copy of walkItems().
     *
     * @param keyItems null for no watchlist check
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
        for (IAEFluidStack ae : list) {
            out.add(ae);
        }
        return out;
    }

    /**
     * Queue one {@code ae2_census} line with the census totals for the writer thread, with one snapshot file of every
     * written grid. The line is logged for a census with no grid too.
     */
    private void emit(int census, List<GridCensus> grids, int gridsSkipped) {
        long nodes = 0;
        long totalItems = 0;
        long patternStacks = 0;
        for (GridCensus grid : grids) {
            nodes += grid.nodeCount();
            totalItems += grid.items().totalItems;
            patternStacks += grid.patterns()
                .countPatternStacks();
        }
        final JsonObject ref = new JsonObject();
        ref.addProperty("census", census);
        ref.addProperty("grids", grids.size());
        ref.addProperty("gridsSkipped", gridsSkipped);
        ref.addProperty("nodes", nodes);
        ref.addProperty("totalItems", totalItems);
        ref.addProperty("patternStacks", patternStacks);

        final String fileStem = "ae2-" + logger.clock()
            .get();
        logger.logSnapshot(new AuditLogger.Snapshot("ae2_census", fileStem, () -> {
            final JsonObject snapshot = new JsonObject();
            snapshot.addProperty("census", census);
            final JsonArray gridArray = new JsonArray();
            for (GridCensus grid : grids) {
                gridArray.add(toGridJson(grid));
            }
            snapshot.add("grids", gridArray);
            return snapshot;
        }, ref));
    }

    /** Return one entry of the census file's {@code grids}. Call on the writer thread. */
    private static JsonObject toGridJson(GridCensus census) {
        final ItemCensus items = census.items();
        final FluidCensus fluids = census.fluids();
        final JsonObject grid = new JsonObject();
        grid.addProperty("gridId", census.gridId());
        final Place anchor = census.anchor();
        if (anchor != null) {
            final JsonArray place = new JsonArray();
            place.add(new JsonPrimitive(anchor.dim()));
            place.add(new JsonPrimitive(anchor.x()));
            place.add(new JsonPrimitive(anchor.y()));
            place.add(new JsonPrimitive(anchor.z()));
            grid.add("anchor", place);
        }
        grid.addProperty("nodes", census.nodeCount());
        grid.addProperty("distinctItems", items.size);
        grid.addProperty("totalItems", items.totalItems);
        grid.addProperty("distinctFluids", fluids.size);
        grid.addProperty("craftableItems", items.countCraftable());
        if (items.countCollapsedNbtVariants() > 0) {
            // NBT variants of one item and meta sum into one key. A verifier reads this count to tell a census that
            // merged variants from one that had none.
            grid.addProperty("nbtVariantsCollapsed", items.countCollapsedNbtVariants());
        }
        final JsonArray itemArr = new JsonArray();
        for (int i = 0; i < items.size; i++) {
            final JsonObject o = new JsonObject();
            o.addProperty("key", items.keys[i]);
            o.addProperty("count", items.counts[i]);
            if (items.craftable[i]) {
                o.addProperty("craftable", true);
            }
            itemArr.add(o);
        }
        grid.add("items", itemArr);
        final JsonArray fluidArr = new JsonArray();
        for (int i = 0; i < fluids.size; i++) {
            final JsonObject o = new JsonObject();
            o.addProperty("fluid", fluids.names[i]);
            o.addProperty("mb", fluids.amounts[i]);
            fluidArr.add(o);
        }
        grid.add("fluids", fluidArr);
        census.patterns()
            .addGridFields(grid);
        return grid;
    }

    /**
     * One census's item side. The arrays that the snapshot supplier reads contain strings and primitives only, with no
     * reference to live game state. The writer thread can therefore serialize the census after the tick of the census
     * ends.
     *
     * <p>
     * A key is {@code modid:name@meta}, with no NBT. The census counts how much of each item a network stores, and
     * no audit reads a tool's durability or a suit's charge. NBT variants of one item and meta therefore sum into
     * one entry, and the totals stay exact. NBT evidence is in the inventory snapshots and the {@code nbt_edit}
     * lines.
     *
     * <p>
     * {@code craftable} is the AE2 flag for an item that the network has a pattern for, or a level emitter set to
     * craft. The AE2 crafting grid is a cell provider on the storage grid, so the storage list has every craftable
     * item. A craftable-only item is an entry with count 0, and a stocked item has the flag on the entry of the item.
     * {@link Ae2PatternCensus} reads the patterns.
     */
    public static final class ItemCensus {

        public String[] keys = new String[64];
        public long[] counts = new long[64];
        public boolean[] craftable = new boolean[64];
        public int size;
        public long totalItems;

        private final KeyCache cache;
        private final KeyItemIndex keyItems;
        private final IndexMap entryIndexById = new IndexMap();
        private int seen;

        /** @param keyItems null for no watchlist check */
        public ItemCensus(KeyCache cache, KeyItemIndex keyItems) {
            this.cache = cache;
            this.keyItems = keyItems;
        }

        /** Source entries that merged into an existing key because they differed only by NBT. */
        public int countCollapsedNbtVariants() {
            return seen - size;
        }

        /** Keys the network could autocraft at census time, stocked or not. */
        public int countCraftable() {
            int craftableCount = 0;
            for (int i = 0; i < size; i++) {
                if (craftable[i]) {
                    craftableCount++;
                }
            }
            return craftableCount;
        }

        // every new key passes this one Sighting, so the walk allocates no Sighting per key
        private static final KeyItemIndex.Sighting AE2_SIGHTING = new KeyItemIndex.Sighting("ae2", null);

        /**
         * Add one storage entry. An entry with the item and meta of an earlier entry is merged into the earlier entry:
         * the counts add, and the craftable flags are ORed.
         */
        public void add(IAEItemStack ae) {
            final Item item = ae.getItem();
            if (item == null) {
                return;
            }
            seen++;
            final long id = packItemId(item, ae.getItemDamage());
            final long count = ae.getStackSize();
            totalItems += count;

            final int existing = entryIndexById.get(id);
            if (existing >= 0) {
                counts[existing] += count;
                craftable[existing] |= ae.isCraftable();
                return;
            }

            final String key = readKey(cache, item, ae.getItemDamage());

            if (size == keys.length) {
                final int grown = size + (size >> 1) + 16;
                keys = Arrays.copyOf(keys, grown);
                counts = Arrays.copyOf(counts, grown);
                craftable = Arrays.copyOf(craftable, grown);
            }
            entryIndexById.put(id, size);
            keys[size] = key;
            counts[size] = count;
            craftable[size] = ae.isCraftable();
            size++;

            if (keyItems != null) {
                keyItems.checkBase(key, key, AE2_SIGHTING);
            }
        }

        /**
         * Return the census key {@code modid:name@meta} of an item and meta, from the cache when the cache has the key.
         */
        public static String readKey(KeyCache cache, Item item, int meta) {
            final long id = packItemId(item, meta);
            String key = cache.get(id);
            if (key == null) {
                key = Item.itemRegistry.getNameForObject(item) + "@" + meta;
                cache.put(id, key);
            }
            return key;
        }

        private static long packItemId(Item item, int meta) {
            return ((long) Item.getIdFromItem(item) << 32) | (meta & 0xFFFFFFFFL);
        }
    }

    /** One census's fluid side, in storage list order. */
    public static final class FluidCensus {

        private String[] names = new String[16];
        private long[] amounts = new long[16];
        private int size;

        private void add(IAEFluidStack ae) {
            if (ae.getFluid() == null) {
                return;
            }
            if (size == names.length) {
                final int grown = size * 2;
                names = Arrays.copyOf(names, grown);
                amounts = Arrays.copyOf(amounts, grown);
            }
            names[size] = ae.getFluid()
                .getName();
            amounts[size] = ae.getStackSize();
            size++;
        }
    }

    /**
     * Persistent (item id, meta) to key string cache. The same few tens of thousands of keys recur on every census
     * for the life of the server, so a key string is built once and later lookups build no string. The table is
     * open-addressed, so a lookup allocates nothing. An empty slot has a null value, so every id, 0 included, is a
     * valid key.
     */
    public static final class KeyCache {

        private long[] ids = new long[1024];
        private String[] values = new String[1024];
        private int size;

        public String get(long id) {
            int i = computeSlot(id, ids.length);
            while (values[i] != null) {
                if (ids[i] == id) {
                    return values[i];
                }
                i = (i + 1) & (ids.length - 1);
            }
            return null;
        }

        public void put(long id, String value) {
            if ((size + 1) * 2 > ids.length) {
                grow();
            }
            insert(id, value);
        }

        private void grow() {
            final long[] oldIds = ids;
            final String[] oldValues = values;
            ids = new long[oldIds.length * 2];
            values = new String[oldValues.length * 2];
            size = 0;
            for (int j = 0; j < oldValues.length; j++) {
                if (oldValues[j] != null) {
                    insert(oldIds[j], oldValues[j]);
                }
            }
        }

        private void insert(long id, String value) {
            int i = computeSlot(id, ids.length);
            while (values[i] != null) {
                if (ids[i] == id) {
                    values[i] = value;
                    return;
                }
                i = (i + 1) & (ids.length - 1);
            }
            ids[i] = id;
            values[i] = value;
            size++;
        }

        // the low bits of a packed id are the meta, and many items share a meta. Fibonacci hashing spreads the whole
        // id over the slot index
        private static int computeSlot(long id, int capacity) {
            return (int) ((id * 0x9E3779B97F4A7C15L) >>> 40) & (capacity - 1);
        }
    }

    /** Per-census (item id, meta) to output index, for merging NBT variants. */
    private static final class IndexMap {

        private long[] ids = new long[1024];
        private int[] values = newFilled(1024);
        private int size;

        private static int[] newFilled(int length) {
            final int[] filled = new int[length];
            Arrays.fill(filled, -1);
            return filled;
        }

        private int get(long id) {
            int i = KeyCache.computeSlot(id, ids.length);
            while (values[i] >= 0) {
                if (ids[i] == id) {
                    return values[i];
                }
                i = (i + 1) & (ids.length - 1);
            }
            return -1;
        }

        private void put(long id, int value) {
            if ((size + 1) * 2 > ids.length) {
                grow();
            }
            insert(id, value);
        }

        private void grow() {
            final long[] oldIds = ids;
            final int[] oldValues = values;
            ids = new long[oldIds.length * 2];
            values = newFilled(oldValues.length * 2);
            size = 0;
            for (int j = 0; j < oldValues.length; j++) {
                if (oldValues[j] >= 0) {
                    insert(oldIds[j], oldValues[j]);
                }
            }
        }

        private void insert(long id, int value) {
            int i = KeyCache.computeSlot(id, ids.length);
            while (values[i] >= 0) {
                if (ids[i] == id) {
                    values[i] = value;
                    return;
                }
                i = (i + 1) & (ids.length - 1);
            }
            ids[i] = id;
            values[i] = value;
            size++;
        }
    }

    /** @param anchor null when no node of the grid has a place */
    private record GridCensus(String gridId, @Nullable Place anchor, int nodeCount, ItemCensus items,
        FluidCensus fluids, Ae2PatternCensus patterns) {}
}
