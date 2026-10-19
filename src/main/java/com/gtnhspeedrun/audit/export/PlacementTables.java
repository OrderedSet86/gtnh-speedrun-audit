package com.gtnhspeedrun.audit.export;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.gtnhspeedrun.audit.core.JsonUtil;

/** Render the placement tables of SUMMARY.txt, MACHINE STATS and TRACKED PLACEMENTS, from the lines of a run. */
public final class PlacementTables {

    private PlacementTables() {}

    /**
     * Append MACHINE STATS: the GT machines and pipes placed in the run, and the multiblocks formed. A placement counts
     * every time, so a machine moved with a wrench counts twice. A multiblock counts once per class and position over
     * the whole run, so a re-formation after a chunk reload adds nothing.
     */
    public static void appendMachineStats(StringBuilder sb, List<JsonObject> lines) {
        final int META_TILE_ID = 3;
        final int TOP_COUNT = 10;
        long machines = 0;
        long pipes = 0;
        final Map<String, Integer> byType = new HashMap<>();
        final Map<String, Set<String>> multiSpots = new HashMap<>();
        for (JsonObject line : lines) {
            final String type = line.get("t")
                .getAsString();
            final JsonObject data = line.getAsJsonObject("data");
            if (type.equals("machine_placed")) {
                if ("pipe".equals(
                    data.get("kind")
                        .getAsString())) {
                    pipes++;
                } else {
                    machines++;
                    final String name = data.has("displayName") ? data.get("displayName")
                        .getAsString()
                        : data.has("itemKey") ? data.get("itemKey")
                            .getAsString() : "?";
                    byType.merge(name, 1, Integer::sum);
                }
            } else if (type.equals("bulk_placed")) {
                // a Matter Manipulator build: [x, y, z, meta tile ID] per block, and names keyed by meta tile ID
                pipes += data.getAsJsonArray("pipes")
                    .size();
                final JsonObject names = data.getAsJsonObject("names");
                for (JsonElement entry : data.getAsJsonArray("machines")) {
                    machines++;
                    final String id = entry.getAsJsonArray()
                        .get(META_TILE_ID)
                        .getAsString();
                    final String name = names.has(id) ? names.get(id)
                        .getAsString() : "gregtech:gt.blockmachines@" + id;
                    byType.merge(name, 1, Integer::sum);
                }
            } else if (type.equals("multiblock_formed")) {
                multiSpots.computeIfAbsent(
                    data.get("class")
                        .getAsString(),
                    k -> new HashSet<>())
                    .add(
                        data.get("dim")
                            .getAsInt() + ":"
                            + data.get("x")
                                .getAsInt()
                            + ","
                            + data.get("y")
                                .getAsInt()
                            + ","
                            + data.get("z")
                                .getAsInt());
            }
        }
        if (machines == 0 && pipes == 0 && multiSpots.isEmpty()) {
            return;
        }
        sb.append("\n== MACHINE STATS ==\n");
        final long multiTotal = multiSpots.values()
            .stream()
            .mapToLong(Set::size)
            .sum();
        sb.append(
            String.format(
                "GT machines placed: %d (%d types)   pipes/cables placed: %d   multiblocks formed: %d (%d classes)%n",
                machines,
                byType.size(),
                pipes,
                multiTotal,
                multiSpots.size()));
        byType.entrySet()
            .stream()
            .sorted(
                Map.Entry.<String, Integer>comparingByValue()
                    .reversed())
            .limit(TOP_COUNT)
            .forEach(e -> sb.append(String.format("  %-40s %d%n", e.getKey(), e.getValue())));
        multiSpots.entrySet()
            .stream()
            .sorted(
                (a, b) -> b.getValue()
                    .size()
                    - a.getValue()
                        .size())
            .limit(TOP_COUNT)
            .forEach(
                e -> sb.append(
                    String.format(
                        "  %-40s %d formed%n",
                        e.getKey(),
                        e.getValue()
                            .size())));
    }

    /**
     * Append TRACKED PLACEMENTS, the count per block of the trackedPlacements setting. The coordinates of each
     * placement are in the JSONL only.
     */
    public static void appendTrackedPlacements(StringBuilder sb, List<JsonObject> lines) {
        final SortedMap<String, Integer> byBlock = new TreeMap<>();
        for (JsonObject line : lines) {
            if (line.get("t")
                .getAsString()
                .equals("block_placed")) {
                byBlock.merge(describePlacedBlock(line.getAsJsonObject("data")), 1, Integer::sum);
            }
        }
        if (byBlock.isEmpty()) {
            return;
        }
        sb.append("\n== TRACKED PLACEMENTS ==\n");
        byBlock.forEach((name, count) -> sb.append(String.format("  %-40s %d%n", name, count)));
    }

    public static String describePlacedBlock(JsonObject data) {
        return data.has("displayName") ? JsonUtil.stripFormatting(
            data.get("displayName")
                .getAsString())
            : data.get("block")
                .getAsString();
    }
}
