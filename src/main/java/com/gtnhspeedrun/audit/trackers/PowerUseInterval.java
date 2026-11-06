package com.gtnhspeedrun.audit.trackers;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * The EU that GT machines consumed in one interval, and the interval's recipe starts. Every {@code eu} includes the
 * {@code wirelessEu} of the same entry. Multiblocks sort by (machine, dim, x, y, z).
 *
 * @param types      per machine type ({@link MachineSite#formatTypeKey}), every GT machine that consumed EU
 * @param multis     one entry per multiblock controller that consumed EU
 * @param readErrors machines and recipe starts whose GT state could not be read
 */
public record PowerUseInterval(SortedMap<String, MachineTotal> types, List<MultiTotal> multis,
    SortedMap<RecipeKey, RecipeTotal> recipes, int readErrors) {

    private static final Comparator<MultiTotal> MULTI_ORDER = Comparator.comparing(
        (MultiTotal multi) -> multi.site()
            .formatTypeKey())
        .thenComparingInt(
            multi -> multi.site()
                .dim())
        .thenComparingInt(
            multi -> multi.site()
                .x())
        .thenComparingInt(
            multi -> multi.site()
                .y())
        .thenComparingInt(
            multi -> multi.site()
                .z());

    public PowerUseInterval {
        types = Collections.unmodifiableSortedMap(new TreeMap<>(types));
        final List<MultiTotal> sortedMultis = new ArrayList<>(multis);
        sortedMultis.sort(MULTI_ORDER);
        multis = List.copyOf(sortedMultis);
        recipes = Collections.unmodifiableSortedMap(new TreeMap<>(recipes));
    }

    public boolean isEmpty() {
        return types.isEmpty() && recipes.isEmpty() && readErrors == 0;
    }

    public BigInteger sumEu() {
        BigInteger sum = BigInteger.ZERO;
        for (MachineTotal total : types.values()) {
            sum = sum.add(total.eu());
        }
        return sum;
    }

    public BigInteger sumWirelessEu() {
        BigInteger sum = BigInteger.ZERO;
        for (MachineTotal total : types.values()) {
            sum = sum.add(total.wirelessEu());
        }
        return sum;
    }

    /** @param machines the tiles of this type that consumed EU */
    public record MachineTotal(int machines, BigInteger eu, BigInteger wirelessEu) {}

    public record MultiTotal(MachineSite site, BigInteger eu, BigInteger wirelessEu) {}

    /** @param outputAmount items or litres of the first output, summed over the starts */
    public record RecipeTotal(long starts, long outputAmount, BigInteger eu) {}
}
