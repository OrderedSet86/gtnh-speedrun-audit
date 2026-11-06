package com.gtnhspeedrun.audit.trackers;

import java.util.Comparator;

/**
 * Name what a recipe start made: the machine type and the recipe's first output. Keys sort by (machine, output).
 *
 * @param machine {@link MachineSite#formatTypeKey}
 * @param output  {@code modid:name@meta} of an item, {@code fluid:<name>} of a fluid, or {@code none}
 */
public record RecipeKey(String machine, String output) implements Comparable<RecipeKey> {

    private static final Comparator<RecipeKey> ORDER = Comparator.comparing(RecipeKey::machine)
        .thenComparing(RecipeKey::output);

    @Override
    public int compareTo(RecipeKey other) {
        return ORDER.compare(this, other);
    }
}
