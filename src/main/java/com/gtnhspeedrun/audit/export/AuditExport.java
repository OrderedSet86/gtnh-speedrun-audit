package com.gtnhspeedrun.audit.export;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.gtnhspeedrun.audit.core.AuditDirs;
import com.gtnhspeedrun.audit.core.JsonUtil;
import com.gtnhspeedrun.audit.core.Verifier;

/**
 * Build the one-file submission bundle: every session log, every snapshot, a new verify report of the whole chain,
 * the anchor values, and a generated SUMMARY.txt. A speedrun.com verifier reads SUMMARY.txt first and opens the JSONL
 * only on suspicion. The export reads files and primitives only, so {@link #writeZip()} can run on a background
 * thread.
 */
public final class AuditExport {

    private final AuditDirs dirs;
    private final AnchorView anchor;

    public AuditExport(AuditDirs dirs, AnchorView anchor) {
        this.dirs = dirs;
        this.anchor = anchor;
    }

    /** Write the bundle to a new zip in the export directory, and return the path of the zip. */
    public Path writeZip() throws IOException {
        Files.createDirectories(dirs.export());
        final DateTimeFormatter fileTime = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmmss");
        final Path zipFile = dirs.export()
            .resolve("audit-export-" + fileTime.format(LocalDateTime.now()) + ".zip");

        final Verifier.Result verify = Verifier.verify(dirs.log(), anchor.worldAuditUuid(), -1);
        final List<JsonObject> lines = readAllLines();

        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(zipFile))) {
            putEntry(zip, "SUMMARY.txt", formatSummary(lines, verify).getBytes(StandardCharsets.UTF_8));
            putEntry(zip, "verify-report.json", formatVerifyJson(verify).getBytes(StandardCharsets.UTF_8));
            putEntry(zip, "anchor.json", formatAnchorJson().getBytes(StandardCharsets.UTF_8));
            putDirectory(zip, dirs.log(), "log/");
            putDirectory(zip, dirs.snapshots(), "snapshots/");
        }
        return zipFile;
    }

    // ------------------------------------------------------------------ pieces

    /**
     * Parse every line of every JSONL file in the log directory, sorted by seq. A line that does not parse, or has no
     * numeric seq, wall or ticks, no string t, or no data object, is skipped.
     */
    private List<JsonObject> readAllLines() throws IOException {
        final List<JsonObject> lines = new ArrayList<>();
        for (Path file : AuditDirs.listEntriesOrEmpty(dirs.log())) {
            if (!file.getFileName()
                .toString()
                .endsWith(".jsonl")) {
                continue;
            }
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.isEmpty()) {
                    continue;
                }
                try {
                    final JsonObject parsed = new JsonParser().parse(line)
                        .getAsJsonObject();
                    if (isChainLine(parsed)) {
                        lines.add(parsed);
                    }
                } catch (JsonParseException | IllegalStateException ignored) {
                    // the corrupt lines are in the problems of verify-report.json
                }
            }
        }
        lines.sort(
            Comparator.comparingLong(
                o -> o.get("seq")
                    .getAsLong()));
        return lines;
    }

    private static boolean isChainLine(JsonObject line) {
        return JsonUtil.hasNumber(line, "seq") && JsonUtil.hasNumber(line, "wall")
            && JsonUtil.hasNumber(line, "ticks")
            && JsonUtil.hasString(line, "t")
            && JsonUtil.hasObject(line, "data");
    }

    // ------------------------------------------------------------------ summary

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

    /**
     * Render SUMMARY.txt: the header, SESSIONS, MILESTONES, MACHINE STATS, TRACKED PLACEMENTS, FLAGS FOR REVIEW and
     * EVENT COUNTS. § codes are stripped from display names, because logs from older versions still contain § codes.
     */
    private String formatSummary(List<JsonObject> lines, Verifier.Result verify) {
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
                            .append(describePlacedBlock(data))
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

        appendMachineStats(sb, lines);
        appendTrackedPlacements(sb, lines);

        sb.append("\n== FLAGS FOR REVIEW ==\n");
        for (Flag flag : FLAGS) {
            sb.append(String.format("%-28s %d%n", flag.label() + ":", counts.getOrDefault(flag.type(), 0)));
        }
        sb.append(
            String.format(
                "%-28s %d%n",
                "/cofh tpx teleports:",
                lines.stream()
                    .filter(AuditExport::isCofhTeleport)
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

    private static String describePlacedBlock(JsonObject data) {
        return data.has("displayName") ? JsonUtil.stripFormatting(
            data.get("displayName")
                .getAsString())
            : data.get("block")
                .getAsString();
    }

    /**
     * Append MACHINE STATS: the GT machines and pipes placed in the run, and the multiblocks formed. A placement counts
     * every time, so a machine moved with a wrench counts twice. A multiblock counts once per class and position over
     * the whole run, so a re-formation after a chunk reload adds nothing.
     */
    private static void appendMachineStats(StringBuilder sb, List<JsonObject> lines) {
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
    private static void appendTrackedPlacements(StringBuilder sb, List<JsonObject> lines) {
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

    private static String formatVerifyJson(Verifier.Result verify) {
        final JsonObject o = new JsonObject();
        o.addProperty("verdict", verify.verdict());
        o.addProperty("lines", verify.lines());
        o.addProperty("firstSeq", verify.firstSeq());
        o.addProperty("lastSeq", verify.lastSeq());
        o.addProperty("lastHash", verify.lastHash());
        final JsonArray problems = new JsonArray();
        for (String problem : verify.problems()) {
            problems.add(JsonUtil.GSON.toJsonTree(problem));
        }
        o.add("problems", problems);
        return JsonUtil.GSON.toJson(o);
    }

    private String formatAnchorJson() {
        final JsonObject o = new JsonObject();
        o.addProperty("worldAuditUuid", anchor.worldAuditUuid());
        o.addProperty("anchorSeq", anchor.seq());
        o.addProperty("anchorTicks", anchor.ticks());
        o.addProperty("anchorOnlineTicks", anchor.onlineTicks());
        o.addProperty("timingStartWallMs", anchor.timingStartWallMs());
        o.addProperty("exportedAtWallMs", System.currentTimeMillis());
        return JsonUtil.GSON.toJson(o);
    }

    // ------------------------------------------------------------------ zip plumbing

    private static void putEntry(ZipOutputStream zip, String name, byte[] bytes) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(bytes);
        zip.closeEntry();
    }

    private static void putDirectory(ZipOutputStream zip, Path dir, String prefix) throws IOException {
        for (Path entry : AuditDirs.listEntriesOrEmpty(dir)) {
            if (Files.isRegularFile(entry)) {
                putEntry(zip, prefix + entry.getFileName(), Files.readAllBytes(entry));
            }
        }
    }

    /** The run state at export time, written to anchor.json. */
    public record AnchorView(String worldAuditUuid, long seq, long ticks, long onlineTicks, long timingStartWallMs) {}

    private record Flag(String type, String label) {}
}
