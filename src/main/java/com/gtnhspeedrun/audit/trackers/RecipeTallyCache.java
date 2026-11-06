package com.gtnhspeedrun.audit.trackers;

import org.jetbrains.annotations.Nullable;

/**
 * Remember one machine's last {@link RecipeTally} and the tally's output, so a repeat recipe builds no
 * {@link RecipeKey}. Outputs are compared by identity: an {@code Item} with the stack's meta, or a {@code Fluid}.
 */
public final class RecipeTallyCache {

    private @Nullable Object outputType;
    private int outputMeta;
    private @Nullable RecipeTally tally;

    /** Return the remembered tally for this output while the tally's interval is open, otherwise null. */
    public @Nullable RecipeTally findTally(@Nullable Object outputType, int outputMeta) {
        if (!(tally != null && tally.isOpen() && outputType == this.outputType && outputMeta == this.outputMeta)) {
            return null;
        }
        return tally;
    }

    public void remember(@Nullable Object outputType, int outputMeta, RecipeTally tally) {
        this.outputType = outputType;
        this.outputMeta = outputMeta;
        this.tally = tally;
    }
}
