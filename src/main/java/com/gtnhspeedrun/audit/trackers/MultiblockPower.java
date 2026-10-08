package com.gtnhspeedrun.audit.trackers;

/**
 * The energy side of a GT multiblock at the moment it formed: how many input and output hatches it has and the
 * highest voltage tier among each. Tier names are GT's VN strings ("LV" … "MAX+"); null means no hatch of that
 * kind with a voltage. Built by the GT mixin, so this class stays GT-free for the sinks that receive it.
 */
public final class MultiblockPower {

    /** Energy hatches, including exotic ones (multi-amp, laser, wireless). */
    public final int energyHatches;
    public final String energyTier;
    public final int dynamoHatches;
    public final String dynamoTier;

    public MultiblockPower(int energyHatches, String energyTier, int dynamoHatches, String dynamoTier) {
        this.energyHatches = energyHatches;
        this.energyTier = energyTier;
        this.dynamoHatches = dynamoHatches;
        this.dynamoTier = dynamoTier;
    }

    /** Part of the formation dedupe key: a hatch-tier change re-logs the formation in the same session. */
    String dedupeSuffix() {
        return ":" + energyTier + "/" + dynamoTier;
    }
}
