package com.gtnhspeedrun.audit.mixins.late.gt;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

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
import com.gtnhspeedrun.audit.trackers.AuditSinks;
import com.gtnhspeedrun.audit.trackers.FormationGuard;
import com.gtnhspeedrun.audit.trackers.GtExtraHatches;
import com.gtnhspeedrun.audit.trackers.GtFormationLogger;
import com.gtnhspeedrun.audit.trackers.MultiblockFootprint;
import com.gtnhspeedrun.audit.trackers.MultiblockPower;
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
 * The formation timestamp for every modern GT multiblock (assembly line, EBF, fusion, …): checkStructure is where
 * mMachine flips, except for TecTech multiblocks, which flip it in their own onPostTick (TTMultiblockBaseMixin logs
 * those through audit$logFormation). The false→true transition is the graded event for the coke%/bbf%/assline% style
 * categories. The hatch lists are filled by the structure check itself, so at RETURN they describe the multiblock that
 * just formed — that is where the voltage it can run at comes from. Coil and structure tiers (StructureTiers) are read
 * at the same moment. Swapping coils or a structure part breaks and re-forms the structure, and the new tiers are part
 * of the dedupe key, so an upgrade logs a fresh formation.
 *
 * <p>
 * The tiers last logged are saved in the controller's NBT, so the re-check after a server restart or chunk
 * reload logs nothing, and a restored backup brings back the marker the backup had.
 *
 * <p>
 * Every server-side check opens a StructureBounds frame, so the formation line carries the box of the positions the
 * check visited, plus the controller's facing. The footprint last logged is saved in NBT as a second key, so a
 * formed multiblock whose footprint changes without a new formation (a wrench rotation, an LSC growing layers) logs a
 * multiblock_footprint line. Coordinates come from this controller's own tile: the Purification Plant runs its units'
 * checkStructure with the plant's tile as the argument.
 */
@Mixin(value = MTEMultiBlockBase.class, remap = false)
public class MTEMultiBlockBaseMixin implements GtFormationLogger {

    @Shadow
    public boolean mMachine;

    @Shadow
    public ArrayList<MTEHatchEnergy> mEnergyHatches;

    @Shadow
    public ArrayList<MTEHatchDynamo> mDynamoHatches;

    @Shadow
    protected List<MTEHatch> mExoticEnergyHatches;

    @Unique
    private static final String AUDIT_FORMED_KEY = "speedrunAuditFormed";

    @Unique
    private static final String AUDIT_FOOTPRINT_KEY = "speedrunAuditFootprint";

    @Unique
    private boolean audit$wasFormed;

    /** The dedupe suffix of the formation last logged for this controller; null before the first. */
    @Unique
    private String audit$loggedFormation;

    /** MultiblockFootprint.key() of the footprint last logged; null before the first. */
    @Unique
    private String audit$loggedFootprint;

    @Inject(method = "saveNBTData", at = @At("TAIL"), require = 1)
    private void audit$saveFormation(NBTTagCompound aNBT, CallbackInfo ci) {
        if (audit$loggedFormation != null) {
            aNBT.setString(AUDIT_FORMED_KEY, audit$loggedFormation);
        }
        if (audit$loggedFootprint != null) {
            aNBT.setString(AUDIT_FOOTPRINT_KEY, audit$loggedFootprint);
        }
    }

    @Inject(method = "loadNBTData", at = @At("TAIL"), require = 1)
    private void audit$loadFormation(NBTTagCompound aNBT, CallbackInfo ci) {
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
    private void audit$before(boolean aForceReset, IGregTechTileEntity aBaseMetaTileEntity,
        CallbackInfoReturnable<Boolean> cir) {
        audit$wasFormed = mMachine;
        StructureBounds.push(this, aBaseMetaTileEntity.isServerSide());
    }

    @Inject(
        method = "checkStructure(ZLgregtech/api/interfaces/tileentity/IGregTechTileEntity;)Z",
        at = @At("RETURN"),
        require = 1)
    private void audit$after(boolean aForceReset, IGregTechTileEntity aBaseMetaTileEntity,
        CallbackInfoReturnable<Boolean> cir) {
        try {
            if (!mMachine || !aBaseMetaTileEntity.isServerSide()) {
                return;
            }
            FormationGuard.log(
                this,
                audit$wasFormed,
                aBaseMetaTileEntity.getWorld().provider.dimensionId,
                aBaseMetaTileEntity.getXCoord(),
                aBaseMetaTileEntity.getYCoord(),
                aBaseMetaTileEntity.getZCoord());
        } finally {
            StructureBounds.pop(this);
        }
    }

    @Override
    public void audit$logFormation() {
        final MetaTileEntity self = (MetaTileEntity) (Object) this;
        final IGregTechTileEntity base = self.getBaseMetaTileEntity();
        final MultiblockFootprint footprint = audit$footprint(base);
        final String logged = AuditSinks.gtMultiblockFormed(
            audit$loggedFormation,
            self.getClass()
                .getSimpleName(),
            AuditSinks.gtLocalName(self),
            base.getWorld().provider.dimensionId,
            base.getXCoord(),
            base.getYCoord(),
            base.getZCoord(),
            audit$power(),
            footprint);
        if (logged == null || logged.equals(audit$loggedFormation)) {
            // an unchanged formation, as after every restart, can still have a new footprint
            audit$logFootprintChange(self, base, footprint);
            return;
        }
        audit$loggedFormation = logged;
        if (footprint.bounds != null) {
            audit$loggedFootprint = footprint.key();
        }
        // the chunk must save for the markers to survive a restart
        base.markDirty();
    }

    @Override
    public void audit$logFootprintChange() {
        final MetaTileEntity self = (MetaTileEntity) (Object) this;
        final IGregTechTileEntity base = self.getBaseMetaTileEntity();
        audit$logFootprintChange(self, base, audit$footprint(base));
    }

    @Unique
    private void audit$logFootprintChange(MetaTileEntity self, IGregTechTileEntity base,
        MultiblockFootprint footprint) {
        // a check that ran no StructureLib walk (no change since the last one, or a hand-written check) has no box
        if (footprint.bounds == null) {
            return;
        }
        final String key = footprint.key();
        if (key.equals(audit$loggedFootprint)) {
            return;
        }
        final boolean logged = AuditSinks.gtMultiblockFootprint(
            self.getClass()
                .getSimpleName(),
            AuditSinks.gtLocalName(self),
            base.getWorld().provider.dimensionId,
            base.getXCoord(),
            base.getYCoord(),
            base.getZCoord(),
            footprint);
        if (logged) {
            audit$loggedFootprint = key;
            base.markDirty();
        }
    }

    /** IAlignment controllers (every StructureLib multiblock) have rotation and flip; the rest only a front facing. */
    @Unique
    private MultiblockFootprint audit$footprint(IGregTechTileEntity base) {
        final int[] bounds = StructureBounds.boundsFor(this, base.getXCoord(), base.getYCoord(), base.getZCoord());
        if ((Object) this instanceof IAlignment alignment) {
            final ExtendedFacing facing = alignment.getExtendedFacing();
            return new MultiblockFootprint(
                bounds,
                facing.getDirection()
                    .name(),
                facing.getRotation()
                    .name(),
                facing.getFlip()
                    .name());
        }
        return new MultiblockFootprint(
            bounds,
            base.getFrontFacing()
                .name(),
            null,
            null);
    }

    /**
     * Each hatch's own voltage, not getMaxInputVoltage(): that one sums voltages across hatches, so four LV
     * hatches would read as one HV. Amperage is summed, so 4 × 16A reads 64. TecTech's own multi-amp and laser
     * lists come in through GtExtraHatches. GT++'s mAllEnergyHatches reports only what also reached the base lists.
     */
    @Unique
    private MultiblockPower audit$power() {
        final Set<MTEHatch> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        final long[] energy = { 0, -1, 0 };
        final long[] dynamo = { 0, -1, 0 };
        audit$scan(mEnergyHatches, true, energy, seen);
        audit$scan(mExoticEnergyHatches, true, energy, seen);
        audit$scan(mDynamoHatches, false, dynamo, seen);
        audit$scan(audit$exoticDynamoHatches(), false, dynamo, seen);
        if (this instanceof GtExtraHatches extra) {
            audit$scan(extra.audit$extraEnergyHatches(), true, energy, seen);
            audit$scan(extra.audit$extraDynamoHatches(), false, dynamo, seen);
        }
        return new MultiblockPower(
            (int) energy[0],
            audit$tierName((int) energy[1]),
            energy[2],
            (int) dynamo[0],
            audit$tierName((int) dynamo[1]),
            dynamo[2],
            StructureTiers.read(this),
            StructureTiers.unreadable(getClass()));
    }

    /** mExoticDynamoHatches is missing from GT 5.09.51 (pack 2.8.4), so it is read by reflection, not shadowed. */
    @Unique
    private static final Field AUDIT_EXOTIC_DYNAMOS = audit$findExoticDynamos();

    @Unique
    private static Field audit$findExoticDynamos() {
        try {
            final Field f = MTEMultiBlockBase.class.getDeclaredField("mExoticDynamoHatches");
            f.setAccessible(true);
            return f;
        } catch (NoSuchFieldException | RuntimeException e) {
            return null;
        }
    }

    @Unique
    private List<? extends MTEHatch> audit$exoticDynamoHatches() {
        if (AUDIT_EXOTIC_DYNAMOS == null) {
            return null;
        }
        try {
            @SuppressWarnings("unchecked")
            final List<? extends MTEHatch> hatches = (List<? extends MTEHatch>) AUDIT_EXOTIC_DYNAMOS.get(this);
            return hatches;
        } catch (IllegalAccessException | RuntimeException e) {
            return null;
        }
    }

    /**
     * Adds each live hatch, once, to {count, highest tier, summed amps}; a hatch with no voltage counts but sets no
     * tier.
     */
    @Unique
    private static void audit$scan(List<? extends MTEHatch> hatches, boolean input, long[] countTierAmps,
        Set<MTEHatch> seen) {
        if (hatches == null) {
            return;
        }
        for (MTEHatch hatch : hatches) {
            final IGregTechTileEntity base = hatch == null ? null : hatch.getBaseMetaTileEntity();
            if (base == null || !seen.add(hatch)) {
                continue;
            }
            countTierAmps[0]++;
            final long voltage = input ? base.getInputVoltage() : base.getOutputVoltage();
            if (voltage > 0) {
                countTierAmps[1] = Math.max(countTierAmps[1], GTUtility.getTier(voltage));
            }
            // the rated amps (a 4A hatch reads 4), not maxAmperesIn's extra allowance for cable loss
            countTierAmps[2] += input ? hatch.maxWorkingAmperesIn() : hatch.maxAmperesOut();
        }
    }

    @Unique
    private static String audit$tierName(int tier) {
        return tier >= 0 && tier < GTValues.VN.length ? GTValues.VN[tier] : null;
    }
}
