package com.gtnhspeedrun.audit.trackers;

/**
 * Log a multiblock formation with the marker, hatch scan and footprint of {@code MTEMultiBlockBase}.
 *
 * <p>
 * A mixin adds this interface to {@code MTEMultiBlockBase}. The mixin of a subclass that forms outside
 * {@code checkStructure}, such as TecTech's {@code TTMultiblockBase}, then calls the same methods.
 */
public interface GtFormationLogger {

    /**
     * Call right after the structure check that formed the multiblock, while the multiblock's
     * {@link StructureBounds} frame is open.
     */
    void audit$logFormation(); // noqa: NAR130

    /** Call after a check of a formed multiblock that left the multiblock formed, while its frame is open. */
    void audit$logFootprintChange(); // noqa: NAR130
}
