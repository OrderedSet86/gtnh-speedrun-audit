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

import com.gtnhspeedrun.audit.trackers.AuditSinks;
import com.gtnhspeedrun.audit.trackers.GtExtraHatches;
import com.gtnhspeedrun.audit.trackers.GtFormationLogger;
import com.gtnhspeedrun.audit.trackers.MultiblockPower;
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
    private boolean audit$wasFormed;

    /** The dedupe suffix of the formation last logged for this controller; null before the first. */
    @Unique
    private String audit$loggedFormation;

    @Inject(method = "saveNBTData", at = @At("TAIL"), require = 1)
    private void audit$saveFormation(NBTTagCompound aNBT, CallbackInfo ci) {
        if (audit$loggedFormation != null) {
            aNBT.setString(AUDIT_FORMED_KEY, audit$loggedFormation);
        }
    }

    @Inject(method = "loadNBTData", at = @At("TAIL"), require = 1)
    private void audit$loadFormation(NBTTagCompound aNBT, CallbackInfo ci) {
        if (aNBT.hasKey(AUDIT_FORMED_KEY)) {
            audit$loggedFormation = aNBT.getString(AUDIT_FORMED_KEY);
        }
    }

    @Inject(
        method = "checkStructure(ZLgregtech/api/interfaces/tileentity/IGregTechTileEntity;)Z",
        at = @At("HEAD"),
        require = 1)
    private void audit$before(boolean aForceReset, IGregTechTileEntity aBaseMetaTileEntity,
        CallbackInfoReturnable<Boolean> cir) {
        audit$wasFormed = mMachine;
    }

    @Inject(
        method = "checkStructure(ZLgregtech/api/interfaces/tileentity/IGregTechTileEntity;)Z",
        at = @At("RETURN"),
        require = 1)
    private void audit$after(boolean aForceReset, IGregTechTileEntity aBaseMetaTileEntity,
        CallbackInfoReturnable<Boolean> cir) {
        if (audit$wasFormed || !mMachine || !aBaseMetaTileEntity.isServerSide()) {
            return;
        }
        audit$logFormation(aBaseMetaTileEntity);
    }

    @Override
    public void audit$logFormation(IGregTechTileEntity aBaseMetaTileEntity) {
        final MetaTileEntity self = (MetaTileEntity) (Object) this;
        final String logged = AuditSinks.gtMultiblockFormed(
            audit$loggedFormation,
            self.getClass()
                .getSimpleName(),
            AuditSinks.gtLocalName(self),
            aBaseMetaTileEntity.getWorld().provider.dimensionId,
            aBaseMetaTileEntity.getXCoord(),
            aBaseMetaTileEntity.getYCoord(),
            aBaseMetaTileEntity.getZCoord(),
            audit$power());
        if (logged != null && !logged.equals(audit$loggedFormation)) {
            audit$loggedFormation = logged;
            // the chunk must save for the marker to survive a restart
            aBaseMetaTileEntity.markDirty();
        }
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
            StructureTiers.read(this));
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
