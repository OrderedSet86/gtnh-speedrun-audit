package com.gtnhspeedrun.audit.trackers;

/**
 * Added to MTEMultiBlockBase by the audit mixin, so a subclass that forms outside checkStructure (TecTech's
 * TTMultiblockBase) logs through the same marker, hatch scan and footprint.
 */
public interface GtFormationLogger {

    /** Called right after the structure check that formed the multiblock, while its StructureBounds frame is open. */
    void audit$logFormation();

    /** Called after a check that found an already-formed multiblock still formed, while its frame is open. */
    void audit$logFootprintChange();
}
