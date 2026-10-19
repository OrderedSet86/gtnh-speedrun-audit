package com.gtnhspeedrun.audit.trackers;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

/**
 * Describe the energy and dynamo hatches and the coil and structure tiers of a GT multiblock at formation, with no GT
 * types in the signature.
 *
 * <p>
 * Each side has the hatch count, the highest voltage tier among the hatches, and the summed amperage. Amperages are
 * plain counts: 16 for a 16A hatch, and 4,194,304 for a 4M-amp laser hatch. Tier names are GT's {@code VN} strings,
 * from {@code ULV} to {@code MAX+}. A null tier means that no hatch of that side has a voltage.
 *
 * @param energyHatches       the energy hatch count, with multi-amp, laser and wireless hatches
 * @param structure           coil and structure tiers by field name, from {@link StructureTiers#read}. The map is
 *                            empty when the controller has no tier fields.
 * @param structureUnreadable the classes whose tier fields could not be read on this side, from
 *                            {@link StructureTiers#unreadable}
 */
public record MultiblockPower(int energyHatches, String energyTier, long energyAmps, int dynamoHatches,
    String dynamoTier, long dynamoAmps, Map<String, String> structure, List<String> structureUnreadable) {

    public MultiblockPower {
        // Map.copyOf() drops the insertion order. dedupeSuffix() and the ledger write the tiers in that order
        structure = Collections.unmodifiableMap(new LinkedHashMap<>(structure));
        structureUnreadable = List.copyOf(structureUnreadable);
    }

    /** Describe a multiblock with energy hatches of one tier, no amperage, no dynamo and no structure tiers. */
    public static MultiblockPower ofEnergyHatches(int hatches, String tier) {
        return ofEnergyHatches(hatches, tier, Collections.emptyMap());
    }

    /** Describe a multiblock with energy hatches of one tier, no amperage and no dynamo. */
    public static MultiblockPower ofEnergyHatches(int hatches, String tier, Map<String, String> structure) {
        return new MultiblockPower(hatches, tier, 0, 0, null, 0, structure, Collections.emptyList());
    }

    /**
     * Return the part of the formation dedupe key that changes with a hatch tier, an amperage, or a coil or structure
     * tier. {@code structureUnreadable} is not in the key, because the list depends on the side the server runs on and
     * not on the structure.
     */
    public String dedupeSuffix() {
        return ":" + energyTier
            + "x"
            + energyAmps
            + "/"
            + dynamoTier
            + "x"
            + dynamoAmps
            + (structure.isEmpty() ? "" : "/" + structure);
    }

    /**
     * Write the hatch counts, then the tiers and amperages that are set, then the structure tiers and the unreadable
     * classes when there are any.
     */
    public void writeTo(JsonObject data) {
        data.addProperty("energyHatches", energyHatches);
        if (energyTier != null) {
            data.addProperty("energyTier", energyTier);
        }
        if (energyAmps > 0) {
            data.addProperty("energyAmps", energyAmps);
        }
        data.addProperty("dynamoHatches", dynamoHatches);
        if (dynamoTier != null) {
            data.addProperty("dynamoTier", dynamoTier);
        }
        if (dynamoAmps > 0) {
            data.addProperty("dynamoAmps", dynamoAmps);
        }
        if (!structure.isEmpty()) {
            final JsonObject tiers = new JsonObject();
            for (Map.Entry<String, String> tier : structure.entrySet()) {
                tiers.addProperty(tier.getKey(), tier.getValue());
            }
            data.add("structure", tiers);
        }
        if (!structureUnreadable.isEmpty()) {
            final JsonArray unreadable = new JsonArray();
            for (String unreadableClass : structureUnreadable) {
                unreadable.add(new JsonPrimitive(unreadableClass));
            }
            data.add("structureUnreadable", unreadable);
        }
    }
}
