package com.gtnhspeedrun.audit.trackers;

import org.jetbrains.annotations.Nullable;

/**
 * Store the {@link PowerTally} of one GT tile on the tile, so {@link PowerUse} finds the tally with no map lookup per
 * tick. With this duck interface, {@link PowerUse} has no GT type.
 */
public interface PowerUseCounter {

    @Nullable
    PowerTally audit$readPowerTally(); // noqa: NAR130

    void audit$writePowerTally(PowerTally tally); // noqa: NAR130
}
