package com.gtnhspeedrun.audit.trackers;

import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;

/**
 * Read the {@link MachineSite} of a GT machine for the GT mixins.
 *
 * <p>
 * The GT types stay in this class. {@link MachineSite} also loads on a server without GT, for the Railcraft and Blood
 * Magic mixins and the self-test.
 */
public final class GtMachineSites {

    private static final String NO_META_TILE_ENTITY = "?";

    private GtMachineSites() {}

    /** Read the site, with the position of the base tile of {@code mte}. */
    public static MachineSite readSite(IMetaTileEntity mte) {
        return readSite(mte, mte.getBaseMetaTileEntity());
    }

    /** Read the site of the MetaTileEntity of {@code base}, or a site with the class name "?" for no MetaTileEntity. */
    public static MachineSite readSite(IGregTechTileEntity base) {
        final IMetaTileEntity mte = base.getMetaTileEntity();
        if (mte == null) {
            return atBase(NO_META_TILE_ENTITY, null, base);
        }
        return readSite(mte, base);
    }

    /** Read the class and the local name from {@code mte}, and the position from {@code base}. */
    public static MachineSite readSite(IMetaTileEntity mte, IGregTechTileEntity base) {
        return atBase(
            mte.getClass()
                .getSimpleName(),
            AuditSinks.gtLocalName(mte),
            base);
    }

    private static MachineSite atBase(String className, String localName, IGregTechTileEntity base) {
        return new MachineSite(
            className,
            localName,
            base.getWorld().provider.dimensionId,
            base.getXCoord(),
            base.getYCoord(),
            base.getZCoord());
    }
}
