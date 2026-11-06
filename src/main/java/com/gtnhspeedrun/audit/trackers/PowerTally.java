package com.gtnhspeedrun.audit.trackers;

/** The EU one GT tile consumed in the current interval of {@link PowerUse}. */
public final class PowerTally {

    private final PowerUseCounter tile;
    /** EU from energy hatches and from the tile's buffer. */
    private final EuSum eu = new EuSum();
    private final EuSum wirelessEu = new EuSum();
    private boolean listed;

    public PowerTally(PowerUseCounter tile) {
        this.tile = tile;
    }

    public PowerUseCounter tile() {
        return tile;
    }

    public EuSum eu() {
        return eu;
    }

    public EuSum wirelessEu() {
        return wirelessEu;
    }

    /** Return whether the tally is in the list of the current interval. */
    public boolean isListed() {
        return listed;
    }

    public void setListed(boolean listed) {
        this.listed = listed;
    }
}
