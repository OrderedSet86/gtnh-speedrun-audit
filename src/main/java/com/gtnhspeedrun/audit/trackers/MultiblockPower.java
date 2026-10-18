package com.gtnhspeedrun.audit.trackers;

/**
 * The energy side of a GT multiblock at the moment it formed: how many input and output hatches it has, the
 * highest voltage tier among each, and their summed amperage (a 16A hatch counts 16, a 4M-amp laser 4,194,304).
 * Tier names are GT's VN strings ("LV" … "MAX+"); null means no hatch of that kind with a voltage. Built by the GT
 * mixin, so this class stays GT-free for the sinks that receive it.
 */
public final class MultiblockPower {

    /** Energy hatches, including exotic ones (multi-amp, laser, wireless). */
    public final int energyHatches;
    public final String energyTier;
    public final long energyAmps;
    public final int dynamoHatches;
    public final String dynamoTier;
    public final long dynamoAmps;
    /** Coil and structure tiers by field name ({@link StructureTiers}); empty when the controller has none. */
    public final java.util.Map<String, String> structure;
    /** Classes whose own tier fields could not be read on this side ({@link StructureTiers#unreadable}). */
    public final java.util.List<String> structureUnreadable;

    public MultiblockPower(int energyHatches, String energyTier, int dynamoHatches, String dynamoTier) {
        this(energyHatches, energyTier, dynamoHatches, dynamoTier, java.util.Collections.emptyMap());
    }

    public MultiblockPower(int energyHatches, String energyTier, int dynamoHatches, String dynamoTier,
        java.util.Map<String, String> structure) {
        this(energyHatches, energyTier, 0, dynamoHatches, dynamoTier, 0, structure);
    }

    public MultiblockPower(int energyHatches, String energyTier, long energyAmps, int dynamoHatches, String dynamoTier,
        long dynamoAmps, java.util.Map<String, String> structure) {
        this(
            energyHatches,
            energyTier,
            energyAmps,
            dynamoHatches,
            dynamoTier,
            dynamoAmps,
            structure,
            java.util.Collections.emptyList());
    }

    public MultiblockPower(int energyHatches, String energyTier, long energyAmps, int dynamoHatches, String dynamoTier,
        long dynamoAmps, java.util.Map<String, String> structure, java.util.List<String> structureUnreadable) {
        this.energyHatches = energyHatches;
        this.energyTier = energyTier;
        this.energyAmps = energyAmps;
        this.dynamoHatches = dynamoHatches;
        this.dynamoTier = dynamoTier;
        this.dynamoAmps = dynamoAmps;
        this.structure = structure;
        this.structureUnreadable = structureUnreadable;
    }

    /**
     * Part of the formation dedupe key: a hatch tier, amperage or coil/structure-tier change re-logs the formation.
     * structureUnreadable is not part of it: it depends on the side the server runs on, not on the structure.
     */
    String dedupeSuffix() {
        return ":" + energyTier
            + "x"
            + energyAmps
            + "/"
            + dynamoTier
            + "x"
            + dynamoAmps
            + (structure.isEmpty() ? "" : "/" + structure);
    }
}
