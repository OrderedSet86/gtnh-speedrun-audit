package com.gtnhspeedrun.audit.compat;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.GZIPOutputStream;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;
import net.minecraft.server.MinecraftServer;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.gtnhspeedrun.audit.GtnhSpeedrunAudit;
import com.gtnhspeedrun.audit.core.JsonUtil;

import appeng.api.AEApi;
import appeng.api.storage.data.IAEItemStack;
import appeng.api.storage.data.IItemList;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/**
 * Scale benchmark for the AE2 census hot loop, so nobody discovers at hour 500 that auditing an endgame base
 * costs TPS. Property-gated exactly like the self-test ({@code -Dspeedrunaudit.ae2bench=<dir>}, or
 * {@code ./gradlew runServer -Pae2bench=<dir>}) and dormant otherwise.
 *
 * <p>
 * It drives {@link Ae2Snapshotter#walkItems} against a synthetic {@link IItemList} built with AE2's own
 * {@code createItemList()} — the same class a live network's {@code getStorageList()} returns — so the
 * measured path is the production one, not a model of it. What it cannot reproduce in a bare dev environment
 * is GTNH's item registry, so entries are real items carrying synthetic damage values; that is faithful for
 * CPU cost (the key is built the same way regardless) but understates JSON size, because GT's registry names
 * are longer than vanilla's. The table reports {@code avgKeyLen} so the size projection can be rescaled.
 *
 * <p>
 * Sizes and the NBT-bearing fraction are overridable: {@code -Dspeedrunaudit.ae2bench.sizes=1000,50000},
 * {@code -Dspeedrunaudit.ae2bench.nbtPercent=20}.
 */
public final class Ae2Bench {

    public static final String PROPERTY = "speedrunaudit.ae2bench";

    private static final int[] DEFAULT_SIZES = { 1_000, 5_000, 20_000, 50_000, 100_000 };
    private static final int WARMUP_RUNS = 3;
    /** NBT variants generated per shared (item, meta) — e.g. one tool at four damage values. */
    private static final int VARIANTS_PER_KEY = 4;
    private static final int MEASURED_RUNS = 5;
    /** Tick budget a server has to hit 20 TPS. The number every walk_ms below is really being compared to. */
    private static final double TICK_BUDGET_MS = 50.0;

    private final MinecraftServer server;
    private final File resultDir;
    private final int[] sizes;
    private final int nbtPercent;
    private boolean done;

    public Ae2Bench(MinecraftServer server, File resultDir) {
        this.server = server;
        this.resultDir = resultDir;
        this.sizes = parseSizes(System.getProperty(PROPERTY + ".sizes"));
        this.nbtPercent = parseInt(System.getProperty(PROPERTY + ".nbtPercent"), 20);
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        // One shot, on the server thread, at the same phase the real census runs at.
        if (event.phase != TickEvent.Phase.END || done) {
            return;
        }
        done = true;
        try {
            run();
        } catch (Throwable t) {
            GtnhSpeedrunAudit.LOG.error("AE2 bench failed", t);
        }
        server.initiateShutdown();
    }

    private void run() throws IOException {
        final List<Item> pool = itemPool();
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

    /**
     * One cell of the sweep. Cold cache is the first census a server ever runs; warm is every census after it,
     * which is the steady state that actually matters over a 500-hour run.
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
            final long allocBefore = threadAllocatedBytes();
            final long t0 = System.nanoTime();
            census = Ae2Snapshotter.walkItems(list, cache, null);
            walkNanos[i] = System.nanoTime() - t0;
            final long alloc = threadAllocatedBytes() - allocBefore;
            if (alloc > 0) {
                allocBytes = allocBytes == 0 ? alloc : Math.min(allocBytes, alloc);
            }
        }
        Arrays.sort(walkNanos);

        // The writer-thread half: same gson tree and gzip the real snapshot path builds.
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
        row.collapsed = census.nbtVariantsCollapsed();
        row.totalItems = census.totalItems;
        row.jsonBytes = json.length;
        row.gzBytes = gzOut.size();
        row.serializeMs = serializeNanos / 1e6;
        row.avgKeyLen = census.size == 0 ? 0 : (double) keyLenTotal / census.size;
        return row;
    }

    /**
     * The evidence that folding NBT variants together costs no accounting accuracy — the claim dropping NBT
     * from the census key rests on. Checked against what {@link #synthesize} is known to have put in, not
     * against a second census implementation: the census must report the exact grand total, exactly one key
     * per (item, meta), and must account for every entry it merged.
     */
    private void checkFidelity(Synth synth, List<String> out) {
        final int entries = synth.entries;
        final Ae2Snapshotter.ItemCensus census = Ae2Snapshotter
            .walkItems(synth.list, new Ae2Snapshotter.KeyCache(), null);

        record(
            out,
            entries,
            "grand total exact",
            census.totalItems == synth.totalItems,
            census.totalItems + " counted vs " + synth.totalItems + " inserted");
        record(
            out,
            entries,
            "one key per item+meta",
            census.size == synth.distinctKeys,
            census.size + " emitted vs " + synth.distinctKeys + " expected");

        final java.util.Set<String> keys = new java.util.HashSet<>();
        long keySum = 0;
        for (int i = 0; i < census.size; i++) {
            keys.add(census.keys[i]);
            keySum += census.counts[i];
        }
        record(
            out,
            entries,
            "no duplicate keys emitted",
            keys.size() == census.size,
            keys.size() + " unique of " + census.size);
        record(
            out,
            entries,
            "per-key counts sum to total",
            keySum == census.totalItems,
            keySum + " vs " + census.totalItems);
        record(
            out,
            entries,
            "collapse accounted for",
            census.nbtVariantsCollapsed() == entries - synth.distinctKeys,
            census.nbtVariantsCollapsed() + " reported vs " + (entries - synth.distinctKeys) + " actual");
        // Guards the four checks above from passing vacuously: before this, the generator gave every entry a
        // unique (item, meta), so nothing ever merged and the collapse path was never actually exercised.
        record(
            out,
            entries,
            "merge path exercised",
            nbtPercent <= 0 || census.nbtVariantsCollapsed() > 0,
            "nbtPercent=" + nbtPercent + " yet nothing collapsed");
    }

    private static void record(List<String> out, int entries, String name, boolean ok, String detail) {
        out.add((ok ? "  ok   " : "  FAIL ") + "@" + entries + " " + name + (ok ? "" : " [" + detail + "]"));
    }

    /** A synthetic network, plus the ground truth about what went into it. */
    private static final class Synth {

        IItemList<IAEItemStack> list;
        /** Entries AE2 actually holds — one per (item, meta, nbt). */
        int entries;
        /** Distinct (item, meta) pairs — what the census must emit, one key each. */
        int distinctKeys;
        /** Exact sum of every stack size inserted. */
        long totalItems;
    }

    /**
     * A synthetic network of n entries. Real items, synthetic damage values — a bare dev environment has a few
     * hundred items where GTNH has tens of thousands, and the key-building cost does not care whether a meta
     * corresponds to a real subitem.
     *
     * <p>
     * The {@code nbtPercent} fraction is generated as {@link #VARIANTS_PER_KEY} NBT variants *sharing* one
     * (item, meta) — a stack of the same tool at different damage values, which is what a real network holds
     * and what the census has to fold back together. Giving every entry its own (item, meta) instead, as an
     * earlier version did, left the merge path untested.
     */
    private Synth synthesize(List<Item> pool, int n) {
        final Synth synth = new Synth();
        synth.list = AEApi.instance()
            .storage()
            .createItemList();

        final int nbtEntries = (int) ((long) n * Math.max(0, nbtPercent) / 100);
        final int plainEntries = n - nbtEntries;
        // Disjoint base-id ranges, so a variant group can never collide with a plain entry's key.
        final int nbtKeys = (nbtEntries + VARIANTS_PER_KEY - 1) / VARIANTS_PER_KEY;

        // Deterministic, so two runs of the bench are comparable. No Random.
        for (int i = 0; i < plainEntries; i++) {
            add(synth, pool, i, i, null);
        }
        for (int i = 0; i < nbtEntries; i++) {
            final int baseId = plainEntries + i / VARIANTS_PER_KEY;
            add(synth, pool, baseId, plainEntries + i, sampleTag(i));
        }
        synth.distinctKeys = plainEntries + nbtKeys;
        // What AE2 actually holds, which is the number the collapse arithmetic has to reconcile against —
        // it can fall short of what was inserted if AE2 merged or rejected any entry.
        synth.entries = synth.list.size();
        return synth;
    }

    /** @param spread seeds the stack size only, so variants of one key still differ in amount */
    private void add(Synth synth, List<Item> pool, int baseId, int spread, NBTTagCompound tag) {
        final Item item = pool.get(baseId % pool.size());
        final int meta = baseId / pool.size();
        final ItemStack stack = new ItemStack(item, 1, meta);
        if (tag != null) {
            stack.setTagCompound(tag);
        }
        final IAEItemStack ae = AEApi.instance()
            .storage()
            .createItemStack(stack);
        if (ae == null) {
            return;
        }
        // Spread across magnitudes: a real network holds both single tools and billions of ingots.
        final long size = 1L + (long) (spread % 977) * (1L + spread % 7919);
        ae.setStackSize(size);
        synth.list.addStorage(ae);
        synth.totalItems += size;
    }

    /** Shaped like a GT tool's tag: a nested compound, an enchantment list, a long charge, a display name. */
    private static NBTTagCompound sampleTag(int seed) {
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

    private static List<Item> itemPool() {
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
        for (Row r : rows) {
            sb.append(
                String.format(
                    "%-9d %-6s %9.3f %9.3f %9.3f %10.2f %9d %9d %8.1f %9.2f%n",
                    r.entries,
                    r.warmCache ? "warm" : "cold",
                    r.walkMsMedian,
                    r.walkMsMin,
                    r.walkMsMax,
                    r.allocBytes / 1048576.0,
                    r.outputKeys,
                    r.collapsed,
                    r.gzBytes / 1024.0,
                    r.serializeMs));
        }
        sb.append("\ntick budget is ")
            .append(TICK_BUDGET_MS)
            .append(" ms; walk_ms is server-thread time, ser_ms runs on the writer thread\n");
        for (Row r : rows) {
            if (r.walkMsMedian > TICK_BUDGET_MS * 0.1) {
                sb.append(
                    String.format(
                        "  OVER 10%% OF TICK: %d entries, cache=%s -> %.1f ms%n",
                        r.entries,
                        r.warmCache ? "warm" : "cold",
                        r.walkMsMedian));
            }
        }
        // 500 h at the 60-minute active cadence. The number that decides whether a submission zip is sane.
        for (Row r : rows) {
            if (r.warmCache) {
                sb.append(
                    String.format(
                        "  projection @%d entries: %.1f MB of ae2 snapshots per 500 h (500 censuses, avgKeyLen %.1f)%n",
                        r.entries,
                        r.gzBytes * 500 / 1048576.0,
                        r.avgKeyLen));
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

        final JsonObject out = new JsonObject();
        out.addProperty("fidelityPassed", fidelityOk);
        final JsonArray fid = new JsonArray();
        for (String line : fidelity) {
            fid.add(new com.google.gson.JsonPrimitive(line.trim()));
        }
        out.add("fidelity", fid);
        out.addProperty("nbtPercent", nbtPercent);
        out.addProperty("tickBudgetMs", TICK_BUDGET_MS);
        final JsonArray arr = new JsonArray();
        for (Row r : rows) {
            final JsonObject o = new JsonObject();
            o.addProperty("entries", r.entries);
            o.addProperty("warmCache", r.warmCache);
            o.addProperty("walkMsMedian", r.walkMsMedian);
            o.addProperty("walkMsMin", r.walkMsMin);
            o.addProperty("walkMsMax", r.walkMsMax);
            o.addProperty("allocBytes", r.allocBytes);
            o.addProperty("outputKeys", r.outputKeys);
            o.addProperty("nbtVariantsCollapsed", r.collapsed);
            o.addProperty("totalItems", r.totalItems);
            o.addProperty("jsonBytes", r.jsonBytes);
            o.addProperty("gzBytes", r.gzBytes);
            o.addProperty("serializeMs", r.serializeMs);
            o.addProperty("avgKeyLen", r.avgKeyLen);
            arr.add(o);
        }
        out.add("rows", arr);

        Files.createDirectories(resultDir.toPath());
        final File file = new File(resultDir, "ae2bench.json");
        try (Writer w = new OutputStreamWriter(Files.newOutputStream(file.toPath()), StandardCharsets.UTF_8)) {
            w.write(JsonUtil.GSON.toJson(out));
        }
        System.out.println("AE2 BENCH WRITTEN " + file.getAbsolutePath());
    }

    private static final class Row {

        int entries;
        boolean warmCache;
        double walkMsMedian;
        double walkMsMin;
        double walkMsMax;
        long allocBytes;
        int outputKeys;
        int collapsed;
        long totalItems;
        int jsonBytes;
        int gzBytes;
        double serializeMs;
        double avgKeyLen;
    }

    // com.sun.management.ThreadMXBean is HotSpot-specific; reached reflectively so a JVM without it degrades
    // to "no allocation figure" instead of failing the whole bench.
    private static Method allocatedBytesMethod;
    private static boolean allocationUnavailable;

    private static long threadAllocatedBytes() {
        if (allocationUnavailable) {
            return -1;
        }
        try {
            if (allocatedBytesMethod == null) {
                allocatedBytesMethod = Class.forName("com.sun.management.ThreadMXBean")
                    .getMethod("getThreadAllocatedBytes", long.class);
            }
            return (Long) allocatedBytesMethod.invoke(
                ManagementFactory.getThreadMXBean(),
                Thread.currentThread()
                    .getId());
        } catch (ReflectiveOperationException | RuntimeException e) {
            allocationUnavailable = true;
            return -1;
        }
    }

    private static int[] parseSizes(String spec) {
        if (spec == null || spec.trim()
            .isEmpty()) {
            return DEFAULT_SIZES;
        }
        final String[] parts = spec.split(",");
        final int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            out[i] = Integer.parseInt(parts[i].trim());
        }
        return out;
    }

    private static int parseInt(String value, int fallback) {
        try {
            return value == null ? fallback : Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
