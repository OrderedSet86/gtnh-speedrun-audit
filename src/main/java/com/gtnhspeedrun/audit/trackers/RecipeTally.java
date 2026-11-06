package com.gtnhspeedrun.audit.trackers;

import java.math.BigInteger;

/**
 * The recipe starts of one {@link RecipeKey} in the current interval of {@link PowerUse}. A machine's
 * {@link RecipeTallyCache} has a reference to the machine's last tally, so a repeat recipe adds to the tally with no
 * map lookup.
 */
public final class RecipeTally {

    private long starts;
    private long outputAmount;
    private final EuSum eu = new EuSum();
    private boolean open = true;

    /**
     * Add one start of a recipe of {@code ticks} ticks at {@code euPerTick} EU per tick. A generator recipe has a
     * negative {@code euPerTick} and counts as a start with no EU.
     *
     * @param outputAmount items or litres of the recipe's first output
     */
    public void addStart(long outputAmount, long euPerTick, long ticks) {
        starts++;
        this.outputAmount = Math.addExact(this.outputAmount, outputAmount);
        if (euPerTick <= 0 || ticks <= 0) {
            return;
        }
        if (euPerTick > Long.MAX_VALUE / ticks) {
            eu.add(
                BigInteger.valueOf(euPerTick)
                    .multiply(BigInteger.valueOf(ticks)));
            return;
        }
        eu.add(euPerTick * ticks);
    }

    /** Add EU drawn when the recipe started, such as the wireless EU an on-demand multiblock takes up front. */
    public void addEu(BigInteger upfrontEu) {
        eu.add(upfrontEu);
    }

    public long starts() {
        return starts;
    }

    public long outputAmount() {
        return outputAmount;
    }

    public EuSum eu() {
        return eu;
    }

    /** Return whether the tally belongs to the current interval. */
    public boolean isOpen() {
        return open;
    }

    public void close() {
        open = false;
    }
}
