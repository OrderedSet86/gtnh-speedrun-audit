package com.gtnhspeedrun.audit.trackers;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Add up the energy and dynamo hatches of one formation scan into a {@link MultiblockPower}, with no GT types in the
 * signature.
 *
 * <p>
 * Each hatch is counted once across both sides and every hatch list, by identity.
 */
public final class HatchScan {

    public static final int NO_TIER = -1;

    private final Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Side energy = new Side();
    private final Side dynamo = new Side();

    /** Return false when the scan has counted {@code hatch} already. */
    public boolean addHatch(Object hatch) {
        return seen.add(hatch);
    }

    /**
     * Count a hatch on the energy side when {@code input} is true, else on the dynamo side.
     *
     * @param tier the hatch's voltage tier, or {@link #NO_TIER} for a hatch with no voltage
     */
    public void count(boolean input, int tier, long amps) {
        final Side side = input ? energy : dynamo;
        side.hatches++;
        side.highestTier = Math.max(side.highestTier, tier);
        side.amps += amps;
    }

    /** @param tierNames the tier names by tier, such as GT's {@code VN} */
    public MultiblockPower buildPower(String[] tierNames, Map<String, String> structure,
        List<String> structureUnreadable) {
        return new MultiblockPower(
            energy.hatches,
            nameTier(tierNames, energy.highestTier),
            energy.amps,
            dynamo.hatches,
            nameTier(tierNames, dynamo.highestTier),
            dynamo.amps,
            structure,
            structureUnreadable);
    }

    private static String nameTier(String[] tierNames, int tier) {
        return tier >= 0 && tier < tierNames.length ? tierNames[tier] : null;
    }

    private static final class Side {

        private int hatches;
        private int highestTier = NO_TIER;
        private long amps;
    }
}
