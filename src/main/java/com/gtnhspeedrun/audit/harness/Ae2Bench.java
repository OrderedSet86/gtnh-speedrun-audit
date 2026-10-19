package com.gtnhspeedrun.audit.harness;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.GZIPOutputStream;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;
import net.minecraft.server.MinecraftServer;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.gtnhspeedrun.audit.GtnhSpeedrunAudit;
import com.gtnhspeedrun.audit.core.JsonUtil;
import com.gtnhspeedrun.audit.snapshot.Ae2Snapshotter;

import appeng.api.AEApi;
import appeng.api.storage.data.IAEItemStack;
import appeng.api.storage.data.IItemList;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/**
 * Benchmark the hot loop of the AE2 census at scale, so the TPS cost of auditing an endgame base is known before
 * hour 500 of a run. The bench runs only when the {@code speedrunaudit.ae2bench} system property names a result
 * directory: {@code -Dspeedrunaudit.ae2bench=<dir>}, or {@code ./gradlew runServer -Pae2bench=<dir>}.
 *
 * <p>
 * The bench calls {@link Ae2Snapshotter#walkItems} over a synthetic {@link IItemList} from the AE2
 * {@code createItemList()}. A live network's {@code getStorageList()} returns the same class, so the walk under test
 * is the production path. A bare dev environment lacks the GTNH item registry, so the entries are real items with
 * synthetic damage values. The measured CPU cost is valid for GTNH, because a key is built the same way for any item.
 * The JSON size is understated, because GT registry names are longer than vanilla names. The projection lines and
 * {@code ae2bench.json} have {@code avgKeyLen}, so the size projection can be rescaled.
 *
 * <p>
 * The sizes and the share of NBT entries can be overridden: {@code -Dspeedrunaudit.ae2bench.sizes=1000,50000},
 * {@code -Dspeedrunaudit.ae2bench.nbtPercent=20}.
 */
public final class Ae2Bench {

    public static final String PROPERTY = "speedrunaudit.ae2bench";

    private static final int[] DEFAULT_SIZES = { 1_000, 5_000, 20_000, 50_000, 100_000 };
    private static final int WARMUP_RUNS = 3;
    private static final int MEASURED_RUNS = 5;
    /** NBT variants generated for each shared (item, meta), such as one tool at four damage values. */
    private static final int VARIANTS_PER_KEY = 4;
    /** One plain entry in this many is also craftable, and one in this many of all entries is craftable only. */
    private static final int CRAFTABLE_EVERY = 10;
    /** The tick length at 20 TPS. Each walk_ms in the report is a share of this budget. */
    private static final double TICK_BUDGET_MS = 50.0;

    private final MinecraftServer server;
    private final Path resultDir;
    private final int[] sizes;
    private final int nbtPercent;
    private boolean done;

    public Ae2Bench(MinecraftServer server, Path resultDir) {
        this.server = server;
        this.resultDir = resultDir;
        this.sizes = parseSizes(System.getProperty(PROPERTY + ".sizes"));
        this.nbtPercent = parseIntOr(System.getProperty(PROPERTY + ".nbtPercent"), 20);
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        // one shot, on the server thread, at the tick phase of the real census
        if (event.phase != TickEvent.Phase.END || done) {
            return;
        }
        done = true;
        try {
            run();
        } catch (IOException | RuntimeException | LinkageError e) { // noqa: NAR142, degrade and report over AE2 storage
                                                                    // code
            GtnhSpeedrunAudit.LOG.error("AE2 bench failed", e);
        }
        server.initiateShutdown();
    }

    private void run() throws IOException {
        final List<Item> pool = readItemPool();
        GtnhSpeedrunAudit.LOG.warn(
            "AE2 BENCH: {} base items available, sizes={}, nbtPercent={}",
            pool.size(),
            Arrays.toString(sizes),
            nbtPercent);

        final List<Row> rows = new ArrayList<>();
        final List<String> fidelity = new ArrayList<>();
        for (int n : sizes) {
            final Synth synth = synthesize(pool, n);
            checkFidelity(synth, fidelity);
            for (boolean warmCache : new boolean[] { false, true }) {
                rows.add(measure(synth, warmCache));
            }
        }
        report(rows, fidelity);
    }

    private static List<Item> readItemPool() {
        final List<Item> pool = new ArrayList<>();
        for (Object o : Item.itemRegistry) {
            if (o instanceof Item item && Item.itemRegistry.getNameForObject(item) != null) {
                pool.add(item);
            }
        }
        if (pool.isEmpty()) {
            throw new IllegalStateException("empty item registry");
        }
        return pool;
    }

    /**
     * Build a synthetic network of {@code n} entries, from real items with synthetic damage values. A bare dev
     * environment has a few hundred items where GTNH has tens of thousands, and the cost of building a key is the
     * same whether or not a meta is a real subitem.
     *
     * <p>
     * The {@code nbtPercent} share of entries is {@link #VARIANTS_PER_KEY} NBT variants of one shared (item, meta),
     * like a stack of one tool at different damage values. A real network stores such stacks, and the census sums
     * them into one key. With a unique (item, meta) for every entry, no entry would merge and the merge path would go
     * untested.
     *
     * <p>
     * Craftables go in through {@code addCrafting()}, the call that the AE2 crafting grid makes into the storage
     * list. {@code addCrafting()} sets the flag on an existing entry, and adds a craftable-only entry with count 0
     * for a new item. One plain entry in {@link #CRAFTABLE_EVERY} is flagged, and some entries are craftable only.
     * The craftable-only entries are part of {@code n}, so the size labels stay true. In each even-numbered variant
     * group only the last variant is flagged, so the census must OR the flags on merge.
     */
    private Synth synthesize(List<Item> pool, int n) {
        final Synth synth = new Synth(
            pool,
            AEApi.instance()
                .storage()
                .createItemList());

        final int nbtEntries = (int) ((long) n * Math.max(0, nbtPercent) / 100);
        final int craftOnlyEntries = n / CRAFTABLE_EVERY;
        final int plainEntries = n - nbtEntries - craftOnlyEntries;
        // the base-id ranges are disjoint, so a variant group can't share a key with a plain entry
        final int nbtKeys = (nbtEntries + VARIANTS_PER_KEY - 1) / VARIANTS_PER_KEY;

        // the entries are deterministic, so two runs of the bench are comparable. No Random
        for (int i = 0; i < plainEntries; i++) {
            synth.addStorage(synth.pick(i, null), i, i % CRAFTABLE_EVERY == 0);
        }
        for (int i = 0; i < nbtEntries; i++) {
            final int group = i / VARIANTS_PER_KEY;
            final boolean craftable = group % 2 == 0 && i % VARIANTS_PER_KEY == VARIANTS_PER_KEY - 1;
            if (synth.addStorage(synth.pick(plainEntries + group, createSampleTag(i)), plainEntries + i, craftable)) {
                synth.mergedCraftableKeys++;
            }
        }
        for (int i = 0; i < craftOnlyEntries; i++) {
            synth.addCraftableOnly(synth.pick(plainEntries + nbtKeys + i, null));
        }
        synth.distinctKeys = plainEntries + nbtKeys + craftOnlyEntries;
        // The collapse arithmetic compares against the size of the AE2 list. The list is shorter than the inserted
        // count when AE2 merged or rejected an entry.
        synth.entries = synth.list.size();
        return synth;
    }

    /** Build a tag shaped like a GT tool's: a nested compound, an enchantment list, a long charge, a display name. */
    private static NBTTagCompound createSampleTag(int seed) {
        final NBTTagCompound tag = new NBTTagCompound();
        final NBTTagCompound stats = new NBTTagCompound();
        stats.setInteger("MaxDamage", 25600);
        stats.setInteger("Damage", seed % 25600);
        stats.setString("Primary", "materials.Neutronium");
        tag.setTag("GT.ToolStats", stats);
        tag.setLong("charge", 100_000_000L + seed);
        tag.setInteger("Damage", seed % 1000);
        tag.setBoolean("Unbreakable", false);
        final NBTTagList ench = new NBTTagList();
        ench.appendTag(new NBTTagString("fortune:3"));
        ench.appendTag(new NBTTagString("efficiency:5"));
        tag.setTag("ench", ench);
        final NBTTagCompound display = new NBTTagCompound();
        display.setString("Name", "Benchmark Item " + seed);
        tag.setTag("display", display);
        return tag;
    }

    /**
     * Check that summing NBT variants into one key loses no count. The census key leaves out NBT, and these checks
     * back that choice. The checks compare the census with what {@link #synthesize} put in: the exact grand total, one
     * key for each (item, meta), an account of every merged entry, and the craftable keys.
     */
    private void checkFidelity(Synth synth, List<String> out) {
        final FidelityLines checks = new FidelityLines(out, synth.entries);
        final Ae2Snapshotter.ItemCensus census = Ae2Snapshotter
            .walkItems(synth.list, new Ae2Snapshotter.KeyCache(), null);

        checks.add(
            "grand total exact",
            census.totalItems == synth.totalItems,
            census.totalItems + " counted vs " + synth.totalItems + " inserted");
        checks.add(
            "one key per item+meta",
            census.size == synth.distinctKeys,
            census.size + " emitted vs " + synth.distinctKeys + " expected");

        final Set<String> keys = new HashSet<>();
        long keySum = 0;
        for (int i = 0; i < census.size; i++) {
            keys.add(census.keys[i]);
            keySum += census.counts[i];
        }
        checks.add("no duplicate keys emitted", keys.size() == census.size, keys.size() + " unique of " + census.size);
        checks.add("per-key counts sum to total", keySum == census.totalItems, keySum + " vs " + census.totalItems);
        checks.add(
            "collapse accounted for",
            census.countCollapsedNbtVariants() == synth.entries - synth.distinctKeys,
            census.countCollapsedNbtVariants() + " reported vs " + (synth.entries - synth.distinctKeys) + " actual");
        // The checks above also pass when no entry merged. Fail when nbtPercent asks for variants and no variant
        // collapsed.
        checks.add(
            "merge path exercised",
            nbtPercent <= 0 || census.countCollapsedNbtVariants() > 0,
            "nbtPercent=" + nbtPercent + " yet nothing collapsed");

        final Set<String> craftable = new HashSet<>();
        for (int i = 0; i < census.size; i++) {
            if (census.craftable[i]) {
                craftable.add(census.keys[i]);
            }
        }
        checks.add(
            "craftable keys exact",
            craftable.equals(synth.craftableKeys),
            craftable.size() + " flagged vs " + synth.craftableKeys.size() + " generated");
        // In a craftable variant group only one variant has the flag, so the census key gets the flag only when the
        // merge ORs the flags. Fail when nbtPercent asks for variants and no variant group was craftable.
        checks.add(
            "craftable merge path exercised",
            nbtPercent <= 0 || synth.mergedCraftableKeys > 0,
            "nbtPercent=" + nbtPercent + " yet no variant group was craftable");
    }

    /**
     * Measure one cell of the sweep. The first census of a server runs with a cold cache. Every later census runs with
     * a warm cache, the steady state over a 500-hour run.
     *
     * @throws IllegalStateException when the in-memory gzip stream throws an {@code IOException}
     */
    private Row measure(Synth synth, boolean warmCache) {
        final IItemList<IAEItemStack> list = synth.list;
        final Ae2Snapshotter.KeyCache shared = new Ae2Snapshotter.KeyCache();
        for (int i = 0; i < WARMUP_RUNS; i++) {
            Ae2Snapshotter.walkItems(list, warmCache ? shared : new Ae2Snapshotter.KeyCache(), null);
        }

        final long[] walkNanos = new long[MEASURED_RUNS];
        long allocBytes = 0;
        Ae2Snapshotter.ItemCensus census = null;
        for (int i = 0; i < MEASURED_RUNS; i++) {
            final Ae2Snapshotter.KeyCache cache = warmCache ? shared : new Ae2Snapshotter.KeyCache();
            final long allocBefore = AllocationMeter.readThreadAllocatedBytes();
            final long t0 = System.nanoTime();
            census = Ae2Snapshotter.walkItems(list, cache, null);
            walkNanos[i] = System.nanoTime() - t0;
            final long alloc = AllocationMeter.readThreadAllocatedBytes() - allocBefore;
            if (alloc > 0) {
                allocBytes = allocBytes == 0 ? alloc : Math.min(allocBytes, alloc);
            }
        }
        Arrays.sort(walkNanos);

        // the writer thread's half: a gson tree and a gzip shaped like the real snapshot's
        final JsonObject snapshot = new JsonObject();
        snapshot.addProperty("gridId", "bench");
        snapshot.addProperty("census", 1);
        final JsonArray itemArr = new JsonArray();
        long keyLenTotal = 0;
        for (int i = 0; i < census.size; i++) {
            final JsonObject o = new JsonObject();
            o.addProperty("key", census.keys[i]);
            o.addProperty("count", census.counts[i]);
            itemArr.add(o);
            keyLenTotal += census.keys[i].length();
        }
        snapshot.add("items", itemArr);

        final long s0 = System.nanoTime();
        final byte[] json = JsonUtil.GSON.toJson(snapshot)
            .getBytes(StandardCharsets.UTF_8);
        final ByteArrayOutputStream gzOut = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(gzOut)) {
            gz.write(json);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        final long serializeNanos = System.nanoTime() - s0;

        final Row row = new Row();
        row.entries = synth.entries;
        row.warmCache = warmCache;
        row.walkMsMedian = walkNanos[MEASURED_RUNS / 2] / 1e6;
        row.walkMsMin = walkNanos[0] / 1e6;
        row.walkMsMax = walkNanos[MEASURED_RUNS - 1] / 1e6;
        row.allocBytes = allocBytes;
        row.outputKeys = census.size;
        row.collapsed = census.countCollapsedNbtVariants();
        row.totalItems = census.totalItems;
        row.jsonBytes = json.length;
        row.gzBytes = gzOut.size();
        row.serializeMs = serializeNanos / 1e6;
        row.avgKeyLen = census.size == 0 ? 0 : (double) keyLenTotal / census.size;
        return row;
    }

    /**
     * Print the sweep table, the over-budget rows, the size projection and the fidelity checks to the log and to
     * stdout, then write the same results to {@code ae2bench.json} in the result directory.
     */
    private void report(List<Row> rows, List<String> fidelity) throws IOException {
        final StringBuilder sb = new StringBuilder(4096);
        sb.append("\n=== AE2 CENSUS BENCH ===\n")
            .append(
                String.format(
                    "%-9s %-6s %9s %9s %9s %10s %9s %9s %8s %9s%n",
                    "entries",
                    "cache",
                    "walk_ms",
                    "min_ms",
                    "max_ms",
                    "alloc_MB",
                    "keys_out",
                    "merged",
                    "gz_KB",
                    "ser_ms"));
        for (Row row : rows) {
            sb.append(
                String.format(
                    "%-9d %-6s %9.3f %9.3f %9.3f %10.2f %9d %9d %8.1f %9.2f%n",
                    row.entries,
                    row.warmCache ? "warm" : "cold",
                    row.walkMsMedian,
                    row.walkMsMin,
                    row.walkMsMax,
                    row.allocBytes / 1048576.0,
                    row.outputKeys,
                    row.collapsed,
                    row.gzBytes / 1024.0,
                    row.serializeMs));
        }
        sb.append("\ntick budget is ")
            .append(TICK_BUDGET_MS)
            .append(" ms; walk_ms is server-thread time, ser_ms runs on the writer thread\n");
        for (Row row : rows) {
            if (row.walkMsMedian > TICK_BUDGET_MS * 0.1) {
                sb.append(
                    String.format(
                        "  OVER 10%% OF TICK: %d entries, cache=%s -> %.1f ms%n",
                        row.entries,
                        row.warmCache ? "warm" : "cold",
                        row.walkMsMedian));
            }
        }
        // 500 h at the default 60-minute active interval is 500 censuses. Project the gzip size of 500 warm
        // censuses, to check that a submission zip stays a sane size.
        for (Row row : rows) {
            if (row.warmCache) {
                sb.append(
                    String.format(
                        "  projection @%d entries: %.1f MB of ae2 snapshots per 500 h (500 censuses, avgKeyLen %.1f)%n",
                        row.entries,
                        row.gzBytes * 500 / 1048576.0,
                        row.avgKeyLen));
            }
        }
        sb.append("\nfidelity — folding NBT variants together must not change what is accounted for:\n");
        boolean fidelityOk = true;
        for (String line : fidelity) {
            sb.append(line)
                .append('\n');
            fidelityOk &= !line.contains("FAIL");
        }
        sb.append(fidelityOk ? "AE2 BENCH FIDELITY PASSED\n" : "AE2 BENCH FIDELITY FAILED\n");

        GtnhSpeedrunAudit.LOG.warn(sb.toString());
        System.out.println(sb);
        writeResultJson(rows, fidelity, fidelityOk);
    }

    /** Write the fidelity lines and one object for each sweep row to {@code ae2bench.json} in the result directory. */
    private void writeResultJson(List<Row> rows, List<String> fidelity, boolean fidelityOk) throws IOException {
        final JsonObject out = new JsonObject();
        out.addProperty("fidelityPassed", fidelityOk);
        final JsonArray fidelityArr = new JsonArray();
        for (String line : fidelity) {
            fidelityArr.add(new JsonPrimitive(line.trim()));
        }
        out.add("fidelity", fidelityArr);
        out.addProperty("nbtPercent", nbtPercent);
        out.addProperty("tickBudgetMs", TICK_BUDGET_MS);
        final JsonArray rowArr = new JsonArray();
        for (Row row : rows) {
            final JsonObject o = new JsonObject();
            o.addProperty("entries", row.entries);
            o.addProperty("warmCache", row.warmCache);
            o.addProperty("walkMsMedian", row.walkMsMedian);
            o.addProperty("walkMsMin", row.walkMsMin);
            o.addProperty("walkMsMax", row.walkMsMax);
            o.addProperty("allocBytes", row.allocBytes);
            o.addProperty("outputKeys", row.outputKeys);
            o.addProperty("nbtVariantsCollapsed", row.collapsed);
            o.addProperty("totalItems", row.totalItems);
            o.addProperty("jsonBytes", row.jsonBytes);
            o.addProperty("gzBytes", row.gzBytes);
            o.addProperty("serializeMs", row.serializeMs);
            o.addProperty("avgKeyLen", row.avgKeyLen);
            rowArr.add(o);
        }
        out.add("rows", rowArr);

        Files.createDirectories(resultDir);
        final Path resultPath = resultDir.resolve("ae2bench.json");
        try (Writer writer = Files.newBufferedWriter(resultPath, StandardCharsets.UTF_8)) {
            writer.write(JsonUtil.GSON.toJson(out));
        }
        System.out.println("AE2 BENCH WRITTEN " + resultPath.toAbsolutePath());
    }

    private static int[] parseSizes(String rawSizes) {
        if (rawSizes == null || rawSizes.trim()
            .isEmpty()) {
            return DEFAULT_SIZES;
        }
        final String[] parts = rawSizes.split(",");
        final int[] parsed = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            parsed[i] = Integer.parseInt(parts[i].trim());
        }
        return parsed;
    }

    private static int parseIntOr(String rawValue, int fallback) {
        try {
            return rawValue == null ? fallback : Integer.parseInt(rawValue.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** A synthetic item list, and the counts and keys that went into the list. */
    private static final class Synth {

        private final List<Item> pool;
        private final IItemList<IAEItemStack> list;
        /** Entries in the AE2 list, one for each (item, meta, NBT). */
        private int entries;
        /** Distinct (item, meta) pairs. The census must emit one key for each. */
        private int distinctKeys;
        /** The sum of every inserted stack size. */
        private long totalItems;
        /** Keys with the craftable flag: stocked and craftable, craftable only, and craftable variant groups. */
        private final Set<String> craftableKeys = new HashSet<>();
        /** Craftable keys whose flag is on one NBT variant of several. */
        private int mergedCraftableKeys;

        private Synth(List<Item> pool, IItemList<IAEItemStack> list) {
            this.pool = pool;
            this.list = list;
        }

        /**
         * Map {@code baseId} to one (item, meta) of the pool, with {@code tag} as the NBT. Distinct ids map to
         * distinct pairs.
         */
        private SynthStack pick(int baseId, NBTTagCompound tag) {
            return new SynthStack(pool.get(baseId % pool.size()), baseId / pool.size(), tag);
        }

        /**
         * @param spread seeds the stack size only, so variants of one key still differ in amount
         * @return whether the entry went in flagged craftable
         */
        private boolean addStorage(SynthStack stack, int spread, boolean craftable) {
            final IAEItemStack ae = AEApi.instance()
                .storage()
                .createItemStack(stack.toItemStack());
            if (ae == null) {
                return false;
            }
            // spread across magnitudes, because a real network stores both single tools and billions of ingots
            final long size = 1L + (long) (spread % 977) * (1L + spread % 7919);
            ae.setStackSize(size);
            list.addStorage(ae);
            totalItems += size;
            if (craftable) {
                list.addCrafting(ae.copy());
                craftableKeys.add(stack.censusKey());
            }
            return craftable;
        }

        private void addCraftableOnly(SynthStack stack) {
            final IAEItemStack ae = AEApi.instance()
                .storage()
                .createItemStack(stack.toItemStack());
            if (ae == null) {
                return;
            }
            list.addCrafting(ae);
            craftableKeys.add(stack.censusKey());
        }
    }

    /** One generated stack: an item, a meta and an NBT tag, or null for no tag. */
    private record SynthStack(Item item, int meta, NBTTagCompound tag) {

        private ItemStack toItemStack() {
            final ItemStack stack = new ItemStack(item, 1, meta);
            if (tag != null) {
                stack.setTagCompound(tag);
            }
            return stack;
        }

        private String censusKey() {
            return Item.itemRegistry.getNameForObject(item) + "@" + meta;
        }
    }

    /** The fidelity lines of one network size. */
    private static final class FidelityLines {

        private final List<String> lines;
        private final int entries;

        private FidelityLines(List<String> lines, int entries) {
            this.lines = lines;
            this.entries = entries;
        }

        private void add(String name, boolean ok, String detail) {
            lines.add((ok ? "  ok   " : "  FAIL ") + "@" + entries + " " + name + (ok ? "" : " [" + detail + "]"));
        }
    }

    /** One cell of the sweep: a network size with a cold or a warm cache. */
    private static final class Row {

        private int entries;
        private boolean warmCache;
        private double walkMsMedian;
        private double walkMsMin;
        private double walkMsMax;
        private long allocBytes;
        private int outputKeys;
        private int collapsed;
        private long totalItems;
        private int jsonBytes;
        private int gzBytes;
        private double serializeMs;
        private double avgKeyLen;
    }
}
