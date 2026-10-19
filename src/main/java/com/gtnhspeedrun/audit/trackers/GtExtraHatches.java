package com.gtnhspeedrun.audit.trackers;

import java.util.List;

import gregtech.api.metatileentity.implementations.MTEHatch;

/**
 * Expose the multi-amp and laser hatches of TecTech's {@code TTMultiblockBase}. A mixin implements this interface on
 * {@code TTMultiblockBase}.
 *
 * <p>
 * These hatches are in {@code eEnergyMulti} and {@code eDynamoMulti}, and not in the hatch lists of
 * {@code MTEMultiBlockBase} that the formation scan reads.
 */
public interface GtExtraHatches {

    List<? extends MTEHatch> audit$extraEnergyHatches(); // noqa: NAR130

    List<? extends MTEHatch> audit$extraDynamoHatches(); // noqa: NAR130
}
