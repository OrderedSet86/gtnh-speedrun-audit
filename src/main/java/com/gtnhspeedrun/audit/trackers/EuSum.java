package com.gtnhspeedrun.audit.trackers;

import java.math.BigInteger;

/**
 * Sum EU exactly past {@link Long#MAX_VALUE}. Endgame wireless drains and interval totals pass 2^63. An add to a sum
 * below 2^63 allocates nothing.
 */
public final class EuSum {

    private long small;
    private BigInteger large = BigInteger.ZERO;

    /** @param eu zero or more */
    public void add(long eu) {
        if (eu > Long.MAX_VALUE - small) {
            large = large.add(BigInteger.valueOf(small))
                .add(BigInteger.valueOf(eu));
            small = 0;
            return;
        }
        small += eu;
    }

    /** @param eu zero or more */
    public void add(BigInteger eu) {
        large = large.add(eu);
    }

    public boolean isZero() {
        return small == 0 && large.signum() == 0;
    }

    /** Return whether {@link #readLong} is the whole sum. */
    public boolean fitsInLong() {
        return large.signum() == 0;
    }

    /** Return the sum when {@link #fitsInLong} is true, otherwise only the part below 2^63. */
    public long readLong() {
        return small;
    }

    public BigInteger toBigInteger() {
        return large.add(BigInteger.valueOf(small));
    }

    public void clear() {
        small = 0;
        large = BigInteger.ZERO;
    }
}
