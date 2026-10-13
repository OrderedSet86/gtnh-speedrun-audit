package com.gtnhspeedrun.audit.trackers;

import gregtech.api.interfaces.tileentity.IGregTechTileEntity;

/**
 * Added to MTEMultiBlockBase by the audit mixin, so a subclass that forms outside checkStructure (TecTech's
 * TTMultiblockBase) logs through the same marker and hatch scan.
 */
public interface GtFormationLogger {

    void audit$logFormation(IGregTechTileEntity aBaseMetaTileEntity);
}
