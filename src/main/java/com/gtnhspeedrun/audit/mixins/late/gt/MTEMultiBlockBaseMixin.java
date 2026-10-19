package com.gtnhspeedrun.audit.mixins.late.gt;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import net.minecraft.nbt.NBTTagCompound;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.gtnewhorizon.structurelib.alignment.IAlignment;
import com.gtnewhorizon.structurelib.alignment.enumerable.ExtendedFacing;
import com.gtnhspeedrun.audit.trackers.FormationGuard;
import com.gtnhspeedrun.audit.trackers.GtExtraHatches;
import com.gtnhspeedrun.audit.trackers.GtFormationLogger;
import com.gtnhspeedrun.audit.trackers.GtMachineSites;
import com.gtnhspeedrun.audit.trackers.HatchScan;
import com.gtnhspeedrun.audit.trackers.MachineSite;
import com.gtnhspeedrun.audit.trackers.MultiblockFootprint;
import com.gtnhspeedrun.audit.trackers.MultiblockPower;
import com.gtnhspeedrun.audit.trackers.MultiblockSinks;
import com.gtnhspeedrun.audit.trackers.StructureBounds;
import com.gtnhspeedrun.audit.trackers.StructureTiers;

import gregtech.api.enums.GTValues;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.metatileentity.MetaTileEntity;
import gregtech.api.metatileentity.implementations.MTEHatch;
import gregtech.api.metatileentity.implementations.MTEHatchDynamo;
import gregtech.api.metatileentity.implementations.MTEHatchEnergy;
import gregtech.api.metatileentity.implementations.MTEMultiBlockBase;
import gregtech.api.util.GTUtility;

/**
 * Logs the formation of every modern GT multiblock (assembly line, EBF, fusion, ...). GT sets mMachine in
 * checkStructure(), except for TecTech multiblocks. TTMultiblockBase sets mMachine in onPostTick(), and
 * TTMultiblockBaseMixin logs TecTech formations through audit$logFormation(). The change of mMachine from false to true
 * is the graded event for categories such as coke%, bbf% and assline%. The structure check fills the hatch lists, so at
 * RETURN the lists contain the hatches of the multiblock that just formed. The voltage tiers of the formation line are
 * read from those lists. Coil and structure tiers (StructureTiers) are read at the same moment. A swap of coils or of a
 * structure part breaks and re-forms the structure. The new tiers are part of the dedupe key, so a new formation is
 * logged after an upgrade.
 *
 * <p>
 * The tiers last logged are saved in the controller's NBT, so no second formation is logged at the re-check after a
 * server restart or chunk reload. Restoring a backup restores the marker that the backup saved.
 *
 * <p>
 * The checkStructure() hook opens a StructureBounds frame for each check, and positions are collected only on the
 * server side. The formation line has the box of the positions that the check visited, and the controller's facing.
 * The footprint last logged is saved in NBT as a second key. When the footprint of a formed multiblock changes without
 * a new formation, the mixin logs a multiblock_footprint line, for example after a wrench rotation or when an LSC
 * (Lapotronic Supercapacitor) grows layers. The formation and footprint lines take their coordinates from the tile of
 * this controller, not from the checkStructure() argument. The Purification Plant passes the plant's tile as that
 * argument when the plant runs the checkStructure() of the plant's units.
 */
@Mixin(value = MTEMultiBlockBase.class, remap = false)
public class MTEMultiBlockBaseMixin implements GtFormationLogger {

    @Shadow
    public boolean mMachine;

    @Shadow
    public ArrayList<MTEHatchEnergy> mEnergyHatches; // noqa: NAR140

    @Shadow
    public ArrayList<MTEHatchDynamo> mDynamoHatches; // noqa: NAR140

    @Shadow
    protected List<MTEHatch> mExoticEnergyHatches;

    @Unique
    private static final String AUDIT_FORMED_KEY = "speedrunAuditFormed";

    @Unique
    private static final String AUDIT_FOOTPRINT_KEY = "speedrunAuditFootprint";

    @Unique
    private boolean audit$wasFormed; // noqa: NAR130

    /** Dedupe suffix of the formation last logged for this controller, or null until a formation is logged. */
    @Unique
    private String audit$loggedFormation; // noqa: NAR130

    /** MultiblockFootprint.formatKey() of the footprint last logged, or null until a footprint is logged. */
    @Unique
    private String audit$loggedFootprint; // noqa: NAR130

    @Inject(method = "saveNBTData", at = @At("TAIL"), require = 1)
    private void audit$saveFormation(NBTTagCompound aNBT, CallbackInfo ci) { // noqa: NAR130
        if (audit$loggedFormation != null) {
            aNBT.setString(AUDIT_FORMED_KEY, audit$loggedFormation);
        }
        if (audit$loggedFootprint != null) {
            aNBT.setString(AUDIT_FOOTPRINT_KEY, audit$loggedFootprint);
        }
    }

    @Inject(method = "loadNBTData", at = @At("TAIL"), require = 1)
    private void audit$loadFormation(NBTTagCompound aNBT, CallbackInfo ci) { // noqa: NAR130
        if (aNBT.hasKey(AUDIT_FORMED_KEY)) {
            audit$loggedFormation = aNBT.getString(AUDIT_FORMED_KEY);
        }
        if (aNBT.hasKey(AUDIT_FOOTPRINT_KEY)) {
            audit$loggedFootprint = aNBT.getString(AUDIT_FOOTPRINT_KEY);
        }
    }

    @Inject(
        method = "checkStructure(ZLgregtech/api/interfaces/tileentity/IGregTechTileEntity;)Z",
        at = @At("HEAD"),
        require = 1)
    private void audit$before(boolean aForceReset, IGregTechTileEntity aBaseMetaTileEntity, // noqa: NAR130
        CallbackInfoReturnable<Boolean> cir) {
        audit$wasFormed = mMachine;
        StructureBounds.push(this, aBaseMetaTileEntity.isServerSide());
    }

    @Inject(
        method = "checkStructure(ZLgregtech/api/interfaces/tileentity/IGregTechTileEntity;)Z",
        at = @At("RETURN"),
        require = 1)
    private void audit$after(boolean aForceReset, IGregTechTileEntity aBaseMetaTileEntity, // noqa: NAR130
        CallbackInfoReturnable<Boolean> cir) {
        try {
            if (!(mMachine && aBaseMetaTileEntity.isServerSide())) {
                return;
            }
            // the formation lines read the site from the base tile of this controller, so this line does too. A
            // Purification Plant passes the plant's tile as the checkStructure() argument of the plant's units
            FormationGuard.log(this, audit$wasFormed, GtMachineSites.readSite((MetaTileEntity) (Object) this));
        } finally {
            StructureBounds.pop(this);
        }
    }

    /**
     * Logs the formation when the hatch, coil or structure tiers differ from the saved marker, then updates the saved
     * markers and marks the tile dirty. For an unchanged formation, only a footprint change is logged.
     */
    @Override
    public void audit$logFormation() {
        final MetaTileEntity self = (MetaTileEntity) (Object) this;
        final IGregTechTileEntity base = self.getBaseMetaTileEntity();
        final MachineSite site = GtMachineSites.readSite(self);
        final MultiblockFootprint footprint = audit$footprint(base, site);
        final String logged = MultiblockSinks.logGtMultiblockFormed(
            audit$loggedFormation,
            new MultiblockSinks.GtFormation(site, audit$power(), footprint));
        if (logged == null || logged.equals(audit$loggedFormation)) {
            // an unchanged formation, such as the first check after a restart, can still have a new footprint
            audit$logFootprintChange(site, base, footprint);
            return;
        }
        audit$loggedFormation = logged;
        if (footprint.bounds != null) {
            audit$loggedFootprint = footprint.formatKey();
        }
        // a chunk that is not marked dirty is not saved, and the markers would be lost on a restart
        base.markDirty();
    }

    @Override
    public void audit$logFootprintChange() {
        final MetaTileEntity self = (MetaTileEntity) (Object) this;
        final IGregTechTileEntity base = self.getBaseMetaTileEntity();
        final MachineSite site = GtMachineSites.readSite(self);
        audit$logFootprintChange(site, base, audit$footprint(base, site));
    }

    /**
     * Logs a multiblock_footprint line when the footprint key differs from the saved key. The new key is saved once the
     * line is logged.
     */
    @Unique
    private void audit$logFootprintChange(MachineSite site, IGregTechTileEntity base, // noqa: NAR130
        MultiblockFootprint footprint) {
        // a check that ran no StructureLib walk has no box: the structure had not changed since the last check, or
        // the controller's check is hand-written
        if (footprint.bounds == null) {
            return;
        }
        final String key = footprint.formatKey();
        if (key.equals(audit$loggedFootprint)) {
            return;
        }
        final boolean logged = MultiblockSinks.logGtMultiblockFootprint(site, footprint);
        if (logged) {
            audit$loggedFootprint = key;
            base.markDirty();
        }
    }

    /**
     * Reads the box and the facing. An IAlignment controller (every StructureLib multiblock) also has a rotation and a
     * flip. Other controllers have only a front facing.
     */
    @Unique
    private MultiblockFootprint audit$footprint(IGregTechTileEntity base, MachineSite site) { // noqa: NAR130
        final int[] bounds = StructureBounds.computeBounds(this, site);
        if ((Object) this instanceof IAlignment alignment) {
            final ExtendedFacing facing = alignment.getExtendedFacing();
            return new MultiblockFootprint(
                bounds,
                facing.getDirection()
                    .name(),
                new MultiblockFootprint.Alignment(
                    facing.getRotation()
                        .name(),
                    facing.getFlip()
                        .name()));
        }
        return MultiblockFootprint.ofFacing(
            bounds,
            base.getFrontFacing()
                .name());
    }

    /**
     * Reads the voltage tier of each hatch with GTUtility.getTier(). getMaxInputVoltage() sums the voltages of all
     * hatches:
     * for four LV hatches, getMaxInputVoltage() returns 128 V, the MV voltage. The amperages are summed, so four 16A
     * hatches count as 64A. The scan reads TecTech's multi-amp and laser hatch lists through GtExtraHatches. For a GT++
     * multiblock, the scan reports only the hatches of mAllEnergyHatches that are also in the base lists.
     */
    @Unique
    private MultiblockPower audit$power() { // noqa: NAR130
        final HatchScan scan = new HatchScan();
        audit$scan(mEnergyHatches, true, scan);
        audit$scan(mExoticEnergyHatches, true, scan);
        audit$scan(mDynamoHatches, false, scan);
        audit$scan(audit$exoticDynamoHatches(), false, scan);
        if (this instanceof GtExtraHatches extra) {
            audit$scan(extra.audit$extraEnergyHatches(), true, scan);
            audit$scan(extra.audit$extraDynamoHatches(), false, scan);
        }
        return scan.buildPower(GTValues.VN, StructureTiers.read(this), StructureTiers.unreadable(getClass()));
    }

    /**
     * GT 5.09.51 (pack 2.8.4) has no mExoticDynamoHatches field, so the field is read by reflection. A @Shadow of the
     * field would fail to apply on that pack.
     */
    @Unique
    private static final Field AUDIT_EXOTIC_DYNAMOS = audit$findExoticDynamos();

    @Unique
    private static Field audit$findExoticDynamos() { // noqa: NAR130
        try {
            final Field f = MTEMultiBlockBase.class.getDeclaredField("mExoticDynamoHatches");
            f.setAccessible(true);
            return f;
        } catch (NoSuchFieldException | RuntimeException e) { // noqa: NAR142, reflection into GT
            return null;
        }
    }

    @Unique
    private List<? extends MTEHatch> audit$exoticDynamoHatches() { // noqa: NAR130
        if (AUDIT_EXOTIC_DYNAMOS == null) {
            return null;
        }
        try {
            @SuppressWarnings("unchecked")
            final List<? extends MTEHatch> hatches = (List<? extends MTEHatch>) AUDIT_EXOTIC_DYNAMOS.get(this);
            return hatches;
        } catch (IllegalAccessException | RuntimeException e) { // noqa: NAR142, reflection into GT
            return null;
        }
    }

    /** Adds each live hatch once to {@code scan}. A hatch with no voltage is counted with no tier. */
    @Unique
    private static void audit$scan(List<? extends MTEHatch> hatches, boolean input, HatchScan scan) { // noqa: NAR130
        if (hatches == null) {
            return;
        }
        for (MTEHatch hatch : hatches) {
            final IGregTechTileEntity base = hatch == null ? null : hatch.getBaseMetaTileEntity();
            if (!(base != null && scan.addHatch(hatch))) {
                continue;
            }
            final long voltage = input ? base.getInputVoltage() : base.getOutputVoltage();
            // maxWorkingAmperesIn() is the rated amperage, 4 on a 4A hatch. maxAmperesIn() adds an allowance for cable
            // loss
            scan.count(
                input,
                voltage > 0 ? GTUtility.getTier(voltage) : HatchScan.NO_TIER,
                input ? hatch.maxWorkingAmperesIn() : hatch.maxAmperesOut());
        }
    }
}
