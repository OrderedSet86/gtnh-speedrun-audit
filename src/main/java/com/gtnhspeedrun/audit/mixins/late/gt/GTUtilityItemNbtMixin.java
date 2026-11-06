package com.gtnhspeedrun.audit.mixins.late.gt;

import java.util.ArrayList;

import net.minecraft.item.ItemStack;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.gtnhspeedrun.audit.trackers.ProspectSinks;

import gregtech.api.util.GTUtility;

/** Reports the scan that an Advanced Seismic Prospector writes to a data stick. */
@Mixin(value = GTUtility.ItemNBT.class, remap = false)
public class GTUtilityItemNbtMixin {

    /** Reports the data that setAdvancedProspectionData() writes to {@code stick}. */
    @Inject(method = "setAdvancedProspectionData", at = @At("HEAD"), require = 1)
    private static void audit$prospectionData(byte tier, ItemStack stick, int x, short y, // noqa: NAR130, NAR131
        int z, int dim, ArrayList<String> oils, ArrayList<String> ores, int radius, CallbackInfo ci) { // noqa: NAR140
        ProspectSinks.reportSeismicData(new ProspectSinks.SeismicData(tier, radius, ores, oils));
    }
}
