package com.gtnhspeedrun.audit.mixins.late.visualprospecting;

import java.util.List;

import net.minecraft.entity.player.EntityPlayerMP;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.gtnhspeedrun.audit.trackers.ProspectSinks;
import com.sinthoras.visualprospecting.VisualProspecting_API;
import com.sinthoras.visualprospecting.database.OreVeinPosition;
import com.sinthoras.visualprospecting.database.UndergroundFluidPosition;

/**
 * Reports the ore veins and fluids of each prospecting result for a player's VisualProspecting map. The vein names and
 * chunk positions are the same in every GT version. The ore data of the scanner packet differs between GT versions.
 */
@Mixin(value = VisualProspecting_API.LogicalServer.class, remap = false)
public class VisualProspectingApiMixin {

    /** Converts the veins and fluids to JSON for {@link ProspectSinks#reportVisualProspecting}. */
    @Inject(method = "sendProspectionResultsToClient", at = @At("HEAD"), require = 1)
    private static void audit$results(EntityPlayerMP player, List<OreVeinPosition> veins, // noqa: NAR130, NAR131
        List<UndergroundFluidPosition> fluids, CallbackInfo ci) {
        final JsonArray veinArray = new JsonArray();
        if (veins != null) {
            for (OreVeinPosition vein : veins) {
                final JsonObject entry = new JsonObject();
                entry.addProperty("name", vein.veinType == null ? null : vein.veinType.name);
                entry.addProperty("dim", vein.dimensionId);
                entry.addProperty("chunkX", vein.chunkX);
                entry.addProperty("chunkZ", vein.chunkZ);
                veinArray.add(entry);
            }
        }
        final JsonArray fluidArray = new JsonArray();
        if (fluids != null) {
            for (UndergroundFluidPosition fluid : fluids) {
                final JsonObject entry = new JsonObject();
                entry.addProperty("fluid", fluid.fluid == null ? null : fluid.fluid.getName());
                entry.addProperty("dim", fluid.dimensionId);
                entry.addProperty("chunkX", fluid.chunkX);
                entry.addProperty("chunkZ", fluid.chunkZ);
                fluidArray.add(entry);
            }
        }
        ProspectSinks.reportVisualProspecting(player, veinArray, fluidArray);
    }
}
