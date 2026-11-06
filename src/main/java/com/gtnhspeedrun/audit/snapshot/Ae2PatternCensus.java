package com.gtnhspeedrun.audit.snapshot;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;

import org.jetbrains.annotations.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.gtnhspeedrun.audit.snapshot.Ae2Snapshotter.KeyCache;

import appeng.api.implementations.ICraftingPatternItem;
import appeng.api.networking.IGridNode;
import appeng.api.networking.crafting.ICraftingGrid;
import appeng.api.networking.crafting.ICraftingPatternDetails;
import appeng.api.networking.pathing.IPathingGrid;
import appeng.api.storage.data.IAEItemStack;
import appeng.api.util.DimensionalCoord;
import appeng.api.util.IInterfaceViewable;
import appeng.me.Grid;
import appeng.me.cache.CraftingGridCache;
import appeng.me.cache.PathGridCache;

/**
 * One grid's channels and patterns, read in the tick of the AE2 census. The fields that the writer thread serializes
 * have strings and primitives only.
 *
 * <p>
 * The patterns are read from the slots of each pattern provider on the grid: every ME interface, part or block, the AE2
 * Fluid Crafting interfaces, GT's Crafting Input Buffer, and GT's Large Molecular Assembler, whose slots are the slots
 * of the assembler's pattern hatches. Each slot gets the pattern's {@code author} tag, the name of the player who
 * encoded the pattern. A pattern encoded before AE2 wrote the tag has none. The outputs come from the crafting grid's
 * pattern details. AE2 compares patterns with the author tag removed, so identical patterns from two players are one
 * detail there, and one entry in {@code patterns}.
 */
public final class Ae2PatternCensus {

    // static: one lookup for the JVM's lifetime. PathGridCache has no getter for the field
    private static Field channelsInUseField;
    // static, with `channelsInUseField`: after a failed lookup, readChannelsInUse() returns -1 with no new lookup
    private static boolean channelsFieldFailed;

    private final int channelsInUse;
    private final String controllerState;
    private final boolean booting;
    private final List<Provider> providers = new ArrayList<>();
    private final List<Pattern> patterns = new ArrayList<>();
    private int patternCount;
    private int readErrors;

    private Ae2PatternCensus(int channelsInUse, String controllerState, boolean booting) {
        this.channelsInUse = channelsInUse;
        this.controllerState = controllerState;
        this.booting = booting;
    }

    /**
     * Read the grid's channel use and every pattern provider's slots. A provider that throws is counted and skipped.
     */
    public static Ae2PatternCensus takePatternCensus(Grid grid, KeyCache cache) {
        final IPathingGrid pathing = grid.getCache(IPathingGrid.class);
        final Ae2PatternCensus census = new Ae2PatternCensus(
            readChannelsInUse(pathing),
            pathing == null ? null : String.valueOf(pathing.getControllerState()),
            pathing != null && pathing.isNetworkBooting());
        final PatternLookup lookup = new PatternLookup(
            mapDetailsByStack(grid.getCache(ICraftingGrid.class)),
            new IdentityHashMap<>(),
            cache);
        final Map<IInterfaceViewable, Boolean> seen = new IdentityHashMap<>();
        for (IGridNode node : grid.getNodes()) {
            if (!(node.getMachine() instanceof IInterfaceViewable provider
                && seen.put(provider, Boolean.TRUE) == null)) {
                continue;
            }
            try {
                census.readProvider(provider, lookup);
            } catch (RuntimeException | LinkageError e) { // noqa: NAR142, degrade and report over provider code
                census.readErrors++;
            }
        }
        return census;
    }

    /** Return -1 when the field can't be read. */
    private static int readChannelsInUse(@Nullable IPathingGrid pathing) {
        if (!(pathing instanceof PathGridCache) || channelsFieldFailed) {
            return -1;
        }
        try {
            if (channelsInUseField == null) {
                channelsInUseField = PathGridCache.class.getDeclaredField("channelsInUse");
                channelsInUseField.setAccessible(true);
            }
            return channelsInUseField.getInt(pathing);
        } catch (ReflectiveOperationException | RuntimeException e) { // noqa: NAR142, reflection into AE2 code
            channelsFieldFailed = true;
            return -1;
        }
    }

    /**
     * Map each pattern stack to the crafting grid's detail for the stack. A detail's getPattern() returns the stack
     * instance in the provider's slot, so the map is by identity.
     */
    private static Map<ItemStack, ICraftingPatternDetails> mapDetailsByStack(@Nullable ICraftingGrid crafting) {
        final Map<ItemStack, ICraftingPatternDetails> byStack = new IdentityHashMap<>();
        if (!(crafting instanceof CraftingGridCache cache)) {
            return byStack;
        }
        for (List<ICraftingPatternDetails> details : cache.getCraftingPatterns()
            .values()) {
            for (ICraftingPatternDetails detail : details) {
                byStack.put(detail.getPattern(), detail);
            }
        }
        return byStack;
    }

    /**
     * Add a {@link Provider} for the pattern slots of {@code provider}, and a {@link Pattern} for each crafting detail
     * that is not in {@code patterns} yet.
     */
    private void readProvider(IInterfaceViewable provider, PatternLookup lookup) {
        final IInventory slots = provider.getPatterns();
        if (slots == null) {
            return;
        }
        final List<PatternSlot> rowSlots = new ArrayList<>();
        for (int slot = 0; slot < slots.getSizeInventory(); slot++) {
            final ItemStack stack = slots.getStackInSlot(slot);
            if (!(stack != null && stack.getItem() instanceof ICraftingPatternItem)) {
                continue;
            }
            patternCount++;
            final String author = stack.hasTagCompound() && stack.getTagCompound()
                .hasKey("author") ? stack.getTagCompound()
                    .getString("author") : null;
            final ICraftingPatternDetails detail = lookup.detailsByStack()
                .get(stack);
            int index = -1;
            if (detail != null) {
                final Integer known = lookup.patternIndex()
                    .get(detail);
                if (known != null) {
                    index = known;
                } else {
                    index = patterns.size();
                    lookup.patternIndex()
                        .put(detail, index);
                    patterns.add(Pattern.read(detail, lookup.cache()));
                }
            }
            rowSlots.add(new PatternSlot(slot, author, index));
        }
        final DimensionalCoord location = provider.getLocation();
        providers.add(
            new Provider(
                provider.getClass()
                    .getSimpleName(),
                location == null ? null : new Place(location.getDimension(), location.x, location.y, location.z),
                List.copyOf(rowSlots)));
    }

    public void addRefFields(JsonObject ref) {
        ref.addProperty("channelsInUse", channelsInUse);
        ref.addProperty("controllerState", controllerState);
        ref.addProperty("booting", booting);
        ref.addProperty("patternProviders", providers.size());
        ref.addProperty("patterns", patternCount);
        ref.addProperty("distinctPatterns", patterns.size());
        if (readErrors > 0) {
            ref.addProperty("patternProviderReadErrors", readErrors);
        }
    }

    /**
     * Add {@code patterns}, each distinct pattern's outputs, and {@code providers}, each provider's place and pattern
     * slots. A slot's {@code pattern} is the index into {@code patterns}, absent when the crafting grid has no detail
     * for the stack.
     *
     * <pre>
     *     "patterns":[{"crafting":false,"outputs":[{"key":"minecraft:nether_star@0","count":1}]}],
     *     "providers":[{"class":"TileInterface","dim":0,"pos":[10,71,10],"slots":[{"slot":0,"author":"Alex","pattern":0}]}]
     * </pre>
     */
    public void addSnapshotFields(JsonObject snapshot) {
        final JsonArray patternArray = new JsonArray();
        for (Pattern pattern : patterns) {
            final JsonObject entry = new JsonObject();
            entry.addProperty("crafting", pattern.crafting());
            final JsonArray outputs = new JsonArray();
            for (Output output : pattern.outputs()) {
                final JsonObject outputEntry = new JsonObject();
                outputEntry.addProperty("key", output.key());
                outputEntry.addProperty("count", output.count());
                outputs.add(outputEntry);
            }
            entry.add("outputs", outputs);
            patternArray.add(entry);
        }
        snapshot.add("patterns", patternArray);
        final JsonArray providerArray = new JsonArray();
        for (Provider provider : providers) {
            final JsonObject entry = new JsonObject();
            entry.addProperty("class", provider.className());
            final Place place = provider.place();
            if (place != null) {
                entry.addProperty("dim", place.dim());
                final JsonArray position = new JsonArray();
                position.add(new JsonPrimitive(place.x()));
                position.add(new JsonPrimitive(place.y()));
                position.add(new JsonPrimitive(place.z()));
                entry.add("pos", position);
            }
            final JsonArray slotArray = new JsonArray();
            for (PatternSlot slot : provider.slots()) {
                final JsonObject slotEntry = new JsonObject();
                slotEntry.addProperty("slot", slot.slot());
                if (slot.author() != null) {
                    slotEntry.addProperty("author", slot.author());
                }
                if (slot.patternIndex() >= 0) {
                    slotEntry.addProperty("pattern", slot.patternIndex());
                }
                slotArray.add(slotEntry);
            }
            entry.add("slots", slotArray);
            providerArray.add(entry);
        }
        snapshot.add("providers", providerArray);
    }

    /** One pattern provider: the provider's class name, the provider's block or null, and the slots with a pattern. */
    private record Provider(String className, @Nullable Place place, List<PatternSlot> slots) {}

    private record Place(int dim, int x, int y, int z) {}

    /**
     * The crafting details of one grid by pattern stack, and the index in {@code patterns} of each detail read so far.
     */
    private record PatternLookup(Map<ItemStack, ICraftingPatternDetails> detailsByStack,
        Map<ICraftingPatternDetails, Integer> patternIndex, KeyCache cache) {}

    private record PatternSlot(int slot, @Nullable String author, int patternIndex) {}

    /** One distinct pattern's outputs, and whether the pattern is a crafting pattern. */
    private record Pattern(List<Output> outputs, boolean crafting) {

        private static Pattern read(ICraftingPatternDetails detail, KeyCache cache) {
            final List<Output> outputs = new ArrayList<>();
            for (IAEItemStack output : detail.getCondensedOutputs()) {
                if (output != null && output.getItem() != null) {
                    outputs.add(
                        new Output(
                            Ae2Snapshotter.ItemCensus.readKey(cache, output.getItem(), output.getItemDamage()),
                            output.getStackSize()));
                }
            }
            return new Pattern(List.copyOf(outputs), detail.isCraftable());
        }
    }

    /** One output of a pattern, as a census key and a count. */
    private record Output(String key, long count) {}
}
