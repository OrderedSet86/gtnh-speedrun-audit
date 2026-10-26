package com.gtnhspeedrun.audit.export;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.gtnhspeedrun.audit.core.ChainFamily;
import com.gtnhspeedrun.audit.core.ForkLink;
import com.gtnhspeedrun.audit.core.JsonUtil;
import com.gtnhspeedrun.audit.core.Verifier;

/** Render SUMMARY.txt, the page of an export bundle that a speedrun.com verifier reads first. */
public final class RunSummary {

    // FLAGS FOR REVIEW, in print order
    private static final List<Flag> FLAGS = List.of(
        new Flag("nei_cheat", "NEI cheat actions"),
        new Flag("nbt_edit", "/nbtedit applications"),
        new Flag("difficulty_change", "difficulty changes"),
        new Flag("nei_packet", "NEI cheat-channel packets"),
        new Flag("gamemode_change", "gamemode changes"),
        new Flag("gt_explosion", "GT machine explosions"),
        new Flag("ae2_census_failed", "AE2 census failures (missing AE2 evidence)"),
        new Flag("multiblock_log_failed", "multiblock formations not recorded (missing evidence)"),
        new Flag("death", "player deaths"),
        new Flag("gamerule_change", "game rule changes"),
        new Flag("gt_global_energy", "wireless EU commands"),
        new Flag("quest_edit", "quest book edits"),
        new Flag("invsee_click", "/inv view item moves"),
        new Flag("trash_insert", "trash can inserts"),
        new Flag("trash_can_closed", "/trash_can uses"),
        new Flag("watched_block", "magic block place/break"),
        new Flag("gt_powerfail", "GT power failures"),
        new Flag("creative_block", "creative block place/break"),
        new Flag("worldedit_edit", "WorldEdit edits"));

    private RunSummary() {}

    /**
     * Render SUMMARY.txt: the header, CHAINS for a bundle with world copies, SESSIONS, MILESTONES, MACHINE STATS,
     * TRACKED PLACEMENTS, FLAGS FOR REVIEW and EVENT COUNTS. § codes are stripped from display names, because logs
     * from older versions still contain § codes.
     */
    public static String formatSummary(SummaryInput input) {
        final List<JsonObject> lines = input.lines();
        final Verifier.Result verify = input.verify();
        final AuditExport.AnchorView anchor = input.anchor();
        final StringBuilder sb = new StringBuilder(16 * 1024);

        sb.append("GTNH SPEEDRUN AUDIT — SUMMARY\n");
        sb.append("world audit uuid : ")
            .append(anchor.worldAuditUuid())
            .append('\n');
        sb.append("chain verdict    : ")
            .append(verify.verdict());
        if (!verify.problems()
            .isEmpty()) {
            sb.append("  (")
                .append(
                    verify.problems()
                        .size())
                .append(" problems, see verify-report.json)");
        }
        sb.append('\n');
        sb.append("events           : ")
            .append(verify.lines())
            .append(" (seq ")
            .append(verify.firstSeq())
            .append("..")
            .append(verify.lastSeq())
            .append(")\n");
        sb.append("IGT (main)       : ")
            .append(formatTicks(anchor.ticks()))
            .append('\n');
        sb.append("player-online    : ")
            .append(formatTicks(anchor.onlineTicks()))
            .append('\n');
        sb.append("RTA              : ")
            .append(
                anchor.timingStartWallMs() == 0 ? "not started"
                    : formatDuration(System.currentTimeMillis() - anchor.timingStartWallMs()) + " (timer started "
                        + formatUtc(anchor.timingStartWallMs())
                        + ")")
            .append('\n');
        sb.append('\n');

        if (input.chains()
            .size() > 1) {
            appendChains(sb, input.chains());
        }

        sb.append("== SESSIONS ==\n");
        final SortedMap<String, Integer> counts = new TreeMap<>();
        long lastStartWall = -1;
        for (JsonObject line : lines) {
            final String type = line.get("t")
                .getAsString();
            counts.merge(type, 1, Integer::sum);
            final JsonObject data = line.getAsJsonObject("data");
            switch (type) {
                case "session_start" -> {
                    lastStartWall = line.get("wall")
                        .getAsLong();
                    sb.append(
                        String.format(
                            "#%-4d start %s  verdict=%s",
                            line.get("sidx")
                                .getAsInt(),
                            formatUtc(
                                line.get("wall")
                                    .getAsLong()),
                            data.get("verifyVerdict")
                                .getAsString()));
                    if (data.has("rollback")) {
                        final JsonObject rollback = data.getAsJsonObject("rollback");
                        sb.append(
                            String.format(
                                "  [%s events rewound, %s real-time gap]",
                                rollback.get("eventsRewound")
                                    .getAsLong(),
                                formatDuration(
                                    rollback.get("wallGapMs")
                                        .getAsLong())));
                    }
                    if (data.has("previousCrashed") && data.get("previousCrashed")
                        .getAsBoolean()) {
                        sb.append("  [previous session CRASHED]");
                    }
                    sb.append('\n');
                }
                case "session_end" -> {
                    final long endWall = line.get("wall")
                        .getAsLong();
                    sb.append(
                        String.format(
                            "      end   %s  uptime %s%s%s%n",
                            formatUtc(endWall),
                            formatTicks(
                                data.get("uptimeTicks")
                                    .getAsLong()),
                            lastStartWall < 0 ? "" : " / " + formatDuration(endWall - lastStartWall) + " real",
                            describeSavingOff(data)));
                }
                default -> {}
            }
        }

        sb.append("\n== MILESTONES ==\n");
        // A Stargate is about 20 tracked blocks. MILESTONES has the first placement of each block, and TRACKED
        // PLACEMENTS has the count of every placement.
        final Set<String> placedSeen = new HashSet<>();
        for (JsonObject line : lines) {
            final String type = line.get("t")
                .getAsString();
            final JsonObject data = line.getAsJsonObject("data");
            final String when = formatUtc(
                line.get("wall")
                    .getAsLong())
                + "  "
                + formatTicks(
                    line.get("ticks")
                        .getAsLong());
            switch (type) {
                case "timing_started" -> sb.append(when)
                    .append("  TIMER      IGT started — first movement by ")
                    .append(
                        data.get("name")
                            .getAsString())
                    .append('\n');
                case "quest_complete" -> sb.append(when)
                    .append("  QUEST      ")
                    .append(
                        JsonUtil.stripFormatting(
                            data.get("questName")
                                .getAsString()))
                    .append('\n');
                case "dim_first_visit" -> sb.append(when)
                    .append("  DIMENSION  ")
                    .append(
                        data.get("dimName")
                            .getAsString())
                    .append(" (")
                    .append(
                        data.get("dim")
                            .getAsInt())
                    .append(") by ")
                    .append(
                        data.get("name")
                            .getAsString())
                    .append('\n');
                case "multiblock_formed" -> {
                    if (data.get("firstOfClass")
                        .getAsBoolean()) {
                        sb.append(when)
                            .append("  MULTIBLOCK ")
                            .append(
                                data.get("class")
                                    .getAsString())
                            .append('\n');
                    }
                }
                case "key_item_first_seen" -> sb.append(when)
                    .append("  KEY ITEM   ")
                    .append(
                        JsonUtil.stripFormatting(
                            data.get("displayName")
                                .getAsString()))
                    .append(" (")
                    .append(
                        data.get("source")
                            .getAsString())
                    .append(")\n");
                case "block_placed" -> {
                    if (placedSeen.add(
                        data.get("block")
                            .getAsString())) {
                        sb.append(when)
                            .append("  PLACED     ")
                            .append(PlacementTables.describePlacedBlock(data))
                            .append(" at dim ")
                            .append(
                                data.get("dim")
                                    .getAsInt())
                            .append(" (")
                            .append(
                                data.get("x")
                                    .getAsInt())
                            .append(", ")
                            .append(
                                data.get("y")
                                    .getAsInt())
                            .append(", ")
                            .append(
                                data.get("z")
                                    .getAsInt())
                            .append(") by ")
                            .append(
                                data.get("name")
                                    .getAsString())
                            .append('\n');
                    }
                }
                default -> {}
            }
        }

        PlacementTables.appendMachineStats(sb, lines);
        PlacementTables.appendTrackedPlacements(sb, lines);

        sb.append("\n== FLAGS FOR REVIEW ==\n");
        for (Flag flag : FLAGS) {
            sb.append(String.format("%-28s %d%n", flag.label() + ":", counts.getOrDefault(flag.type(), 0)));
        }
        sb.append(
            String.format(
                "%-28s %d%n",
                "/cofh tpx teleports:",
                lines.stream()
                    .filter(RunSummary::isCofhTeleport)
                    .count()));
        for (JsonObject line : lines) {
            final String type = line.get("t")
                .getAsString();
            final boolean flaggedJoin = type.equals("client_mods") && line.getAsJsonObject("data")
                .has("flagged");
            if (type.equals("gamemode_change") || type.equals("nei_cheat")
                || type.equals("nbt_edit")
                || type.equals("difficulty_change")
                || type.equals("gamerule_change")
                || type.equals("gt_global_energy")
                || type.equals("quest_edit")
                || (type.equals("invsee_close") && invseeNeedsReview(line.getAsJsonObject("data")))
                || flaggedJoin
                || isCofhTeleport(line)) {
                // the line goes into SUMMARY.txt without NBT payloads and mod lists. The JSONL has the full line
                final JsonObject printable = withoutBulk(line.getAsJsonObject("data"));
                sb.append("  ")
                    .append(
                        formatUtc(
                            line.get("wall")
                                .getAsLong()))
                    .append("  ")
                    .append(type)
                    .append("  ")
                    .append(JsonUtil.GSON.toJson(printable))
                    .append('\n');
            }
        }

        sb.append("\n== EVENT COUNTS ==\n");
        counts.forEach((type, count) -> sb.append(String.format("%-24s %d%n", type, count)));

        sb.append("\nRead VERIFIERS.md in the mod repository for how to independently re-verify this bundle.\n");
        return sb.toString();
    }

    /**
     * Append CHAINS: one row per chain, the exported chain first. A world copy's row has the parent chain and the seq
     * and IGT of the copied save.
     */
    private static void appendChains(StringBuilder sb, List<AuditExport.ChainReport> chains) {
        sb.append("== CHAINS ==\n");
        for (AuditExport.ChainReport report : chains) {
            final ChainFamily.FamilyChain chain = report.chain();
            sb.append(
                String.format(
                    "%s  folder %s  verdict %s  lines %d",
                    chain.uuid(),
                    chain.folder() == null ? "?" : chain.folder(),
                    report.verify()
                        .verdict(),
                    report.verify()
                        .lines()));
            final ForkLink link = chain.link();
            if (link != null) {
                sb.append(
                    String.format(
                        "  copy of %s at seq %d %s, link %s",
                        link.parentUuid(),
                        link.parentSave()
                            .seq(),
                        formatTicks(
                            link.parentSave()
                                .ticks()),
                        report.parentLinkOk() ? "OK" : "BROKEN"));
            }
            sb.append(report == chains.get(0) ? "  (exported)\n" : "\n");
        }
        sb.append("SESSIONS has the exported chain's ancestors up to each copied save, then the exported chain.\n\n");
    }

    private static final DateTimeFormatter UTC_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'")
        .withZone(ZoneOffset.UTC);

    private static String formatUtc(long wallMs) {
        return UTC_TIME.format(Instant.ofEpochMilli(wallMs));
    }

    private static String formatTicks(long ticks) {
        final long TICKS_PER_SECOND = 20;
        final long seconds = ticks / TICKS_PER_SECOND;
        return String.format("[%dh%02dm%02ds igt]", seconds / 3600, seconds % 3600 / 60, seconds % 60);
    }

    private static String formatDuration(long ms) {
        final long MS_PER_SECOND = 1000;
        final long seconds = ms / MS_PER_SECOND;
        if (seconds >= 3600) {
            return String.format("%dh%02dm%02ds", seconds / 3600, seconds % 3600 / 60, seconds % 60);
        }
        return String.format("%dm%02ds", seconds / 60, seconds % 60);
    }

    /**
     * Return the SESSIONS note for a stop with overworld saving off, or an empty string when overworld saving was on.
     * With saving off, the shutdown save skipped the overworld and the anchor.
     */
    private static String describeSavingOff(JsonObject sessionEnd) {
        boolean overworldOff = false;
        if (sessionEnd.has("savingOffDims")) {
            for (JsonElement dim : sessionEnd.getAsJsonArray("savingOffDims")) {
                overworldOff |= dim.getAsInt() == 0;
            }
        }
        if (!overworldOff) {
            return "";
        }
        final boolean backup = sessionEnd.has("backupRunning") && sessionEnd.get("backupRunning")
            .getAsBoolean();
        return "  [world saving OFF" + (backup ? ", backup running" : "") + ": not saved at stop]";
    }

    /**
     * Return whether {@code line} is a /cofh tpx command. CoFHCore's /cofh tpx teleports to any player, dimension or
     * position, so the command can skip the travel into a dimension.
     */
    private static boolean isCofhTeleport(JsonObject line) {
        if (!"command".equals(
            line.get("t")
                .getAsString())) {
            return false;
        }
        final JsonObject data = line.getAsJsonObject("data");
        if (!(data.has("cmd") && "cofh".equals(
            data.get("cmd")
                .getAsString())
            && data.has("args"))) {
            return false;
        }
        final JsonArray args = data.getAsJsonArray("args");
        return args.size() > 0 && "tpx".equalsIgnoreCase(
            args.get(0)
                .getAsString());
    }

    /**
     * Return whether an /inv view dropped or overwrote changes on the target side. Such a view duplicates or loses
     * items.
     */
    private static boolean invseeNeedsReview(JsonObject close) {
        final String outcome = close.has("outcome") ? close.get("outcome")
            .getAsString() : "";
        return outcome.startsWith("changes") || outcome.contains("overwritten") || outcome.contains("last writer");
    }

    private static final Set<String> BULKY_KEYS = Set.of("nbtB64", "beforeB64", "mods", "payloadB64", "tileNbtB64");

    /**
     * Copy {@code in} without NBT payloads and mod lists, at any depth. An NEI delete line has whole stacks with their
     * NBT. The copy goes entry by entry, because the Gson 2.2.4 of Minecraft has no public deepCopy().
     */
    private static JsonObject withoutBulk(JsonObject in) {
        final JsonObject out = new JsonObject();
        for (Map.Entry<String, JsonElement> e : in.entrySet()) {
            if (!BULKY_KEYS.contains(e.getKey())) {
                out.add(e.getKey(), withoutBulk(e.getValue()));
            }
        }
        return out;
    }

    private static JsonElement withoutBulk(JsonElement in) {
        if (in.isJsonObject()) {
            return withoutBulk(in.getAsJsonObject());
        }
        if (in.isJsonArray()) {
            final JsonArray out = new JsonArray();
            for (JsonElement e : in.getAsJsonArray()) {
                out.add(withoutBulk(e));
            }
            return out;
        }
        return in;
    }

    private record Flag(String type, String label) {}

    /**
     * The inputs of SUMMARY.txt.
     *
     * @param lines  the lineage lines, oldest chain first
     * @param chains the chains of the bundle, the exported chain first
     */
    public record SummaryInput(List<JsonObject> lines, Verifier.Result verify, AuditExport.AnchorView anchor,
        List<AuditExport.ChainReport> chains) {

        public SummaryInput {
            lines = List.copyOf(lines);
            chains = List.copyOf(chains);
        }
    }
}
