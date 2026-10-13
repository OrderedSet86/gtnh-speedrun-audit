package com.gtnhspeedrun.audit.trackers;

import java.util.List;

import gregtech.api.metatileentity.implementations.MTEHatch;

/**
 * Added to TecTech's TTMultiblockBase by the audit mixin: the multi-amp and laser hatches it keeps in its own
 * lists (eEnergyMulti, eDynamoMulti), which never reach the GT base lists the formation scan reads.
 */
public interface GtExtraHatches {

    List<? extends MTEHatch> audit$extraEnergyHatches();

    List<? extends MTEHatch> audit$extraDynamoHatches();
}
