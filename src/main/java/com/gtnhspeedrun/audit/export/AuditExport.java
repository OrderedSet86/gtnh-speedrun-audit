package com.gtnhspeedrun.audit.export;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.gtnhspeedrun.audit.core.JsonUtil;
import com.gtnhspeedrun.audit.core.Verifier;

/**
 * The one-file submission bundle: every session log, every snapshot, a fresh full-chain verify report, the
 * anchor's view of the world, and a generated SUMMARY.txt — the artifact a speedrun.com verifier reads first,
 * drilling into the JSONL only on suspicion. Built entirely from files + primitives on a background thread.
 */
public final class AuditExport {

    private final File auditDir;
    private final File logDir;
    private final File snapshotDir;
    private final String worldAuditUuid;
    private final long anchorSeq;
    private final long anchorTicks;

    public AuditExport(File auditDir, File logDir, File snapshotDir, String worldAuditUuid, long anchorSeq,
        long anchorTicks) {
        this.auditDir = auditDir;
        this.logDir = logDir;
        this.snapshotDir = snapshotDir;
        this.worldAuditUuid = worldAuditUuid;
        this.anchorSeq = anchorSeq;
        this.anchorTicks = anchorTicks;
    }

    /** @return the created zip */
    public File run() throws IOException {
        final File exportDir = new File(auditDir, "export");
        exportDir.mkdirs();
        final SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd-HHmmss");
        final File zipFile = new File(exportDir, "audit-export-" + fmt.format(new Date()) + ".zip");

        final Verifier.Result verify = Verifier.verify(logDir, worldAuditUuid, -1);
        final List<JsonObject> lines = readAllLines();

        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(zipFile))) {
            put(zip, "SUMMARY.txt", summary(lines, verify).getBytes(StandardCharsets.UTF_8));
            put(zip, "verify-report.json", verifyJson(verify).getBytes(StandardCharsets.UTF_8));
            put(zip, "anchor.json", anchorJson().getBytes(StandardCharsets.UTF_8));
            addDir(zip, logDir, "log/");
            addDir(zip, snapshotDir, "snapshots/");
        }
        return zipFile;
    }

    // ------------------------------------------------------------------ pieces

    private String anchorJson() {
        final JsonObject o = new JsonObject();
        o.addProperty("worldAuditUuid", worldAuditUuid);
        o.addProperty("anchorSeq", anchorSeq);
        o.addProperty("anchorTicks", anchorTicks);
        o.addProperty("exportedAtWallMs", System.currentTimeMillis());
        return JsonUtil.GSON.toJson(o);
    }

    private static String verifyJson(Verifier.Result verify) {
        final JsonObject o = new JsonObject();
        o.addProperty("verdict", verify.verdict);
        o.addProperty("lines", verify.lines);
        o.addProperty("firstSeq", verify.firstSeq);
        o.addProperty("lastSeq", verify.lastSeq);
        o.addProperty("lastHash", verify.lastHash);
        final com.google.gson.JsonArray problems = new com.google.gson.JsonArray();
        for (String p : verify.problems) {
            problems.add(JsonUtil.GSON.toJsonTree(p));
        }
        o.add("problems", problems);
        return JsonUtil.GSON.toJson(o);
    }

    private List<JsonObject> readAllLines() throws IOException {
        final List<JsonObject> lines = new ArrayList<>();
        final File[] files = logDir.listFiles((d, n) -> n.endsWith(".jsonl"));
        if (files != null) {
            for (File f : files) {
                for (String line : Files.readAllLines(f.toPath(), StandardCharsets.UTF_8)) {
                    if (line.isEmpty()) {
                        continue;
                    }
                    try {
                        lines.add(
                            new JsonParser().parse(line)
                                .getAsJsonObject());
                    } catch (RuntimeException ignored) {
                        // The verify report already flags corrupt lines.
                    }
                }
            }
        }
        lines.sort(
            Comparator.comparingLong(
                o -> o.get("seq")
                    .getAsLong()));
        return lines;
    }

    // ------------------------------------------------------------------ summary

    private String summary(List<JsonObject> lines, Verifier.Result verify) {
        final StringBuilder sb = new StringBuilder(16 * 1024);
        final SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss 'UTC'");
        fmt.setTimeZone(TimeZone.getTimeZone("UTC"));

        sb.append("GTNH SPEEDRUN AUDIT — SUMMARY\n");
        sb.append("world audit uuid : ")
            .append(worldAuditUuid)
            .append('\n');
        sb.append("chain verdict    : ")
            .append(verify.verdict);
        if (!verify.problems.isEmpty()) {
            sb.append("  (")
                .append(verify.problems.size())
                .append(" problems, see verify-report.json)");
        }
        sb.append('\n');
        sb.append("events           : ")
            .append(verify.lines)
            .append(" (seq ")
            .append(verify.firstSeq)
            .append("..")
            .append(verify.lastSeq)
            .append(")\n");
        sb.append("run clock        : ")
            .append(ticksHuman(anchorTicks))
            .append('\n');
        sb.append('\n');

        sb.append("== SESSIONS ==\n");
        final Map<String, Integer> counts = new HashMap<>();
        for (JsonObject line : lines) {
            final String type = line.get("t")
                .getAsString();
            counts.merge(type, 1, Integer::sum);
            final JsonObject data = line.getAsJsonObject("data");
            switch (type) {
                case "session_start" -> {
                    sb.append(
                        String.format(
                            "#%-4d start %s  verdict=%s",
                            line.get("sidx")
                                .getAsInt(),
                            fmt.format(
                                new Date(
                                    line.get("wall")
                                        .getAsLong())),
                            data.get("verifyVerdict")
                                .getAsString()));
                    if (data.has("rollback")) {
                        final JsonObject rb = data.getAsJsonObject("rollback");
                        sb.append(
                            String.format(
                                "  [%s events rewound, %s real-time gap]",
                                rb.get("eventsRewound")
                                    .getAsLong(),
                                msHuman(
                                    rb.get("wallGapMs")
                                        .getAsLong())));
                    }
                    if (data.has("previousCrashed") && data.get("previousCrashed")
                        .getAsBoolean()) {
                        sb.append("  [previous session CRASHED]");
                    }
                    sb.append('\n');
                }
                case "session_end" -> sb.append(
                    String.format(
                        "      end   %s  uptime %s%n",
                        fmt.format(
                            new Date(
                                line.get("wall")
                                    .getAsLong())),
                        ticksHuman(
                            data.get("uptimeTicks")
                                .getAsLong())));
                default -> {}
            }
        }

        sb.append("\n== MILESTONES ==\n");
        for (JsonObject line : lines) {
            final String type = line.get("t")
                .getAsString();
            final JsonObject data = line.getAsJsonObject("data");
            final String when = fmt.format(
                new Date(
                    line.get("wall")
                        .getAsLong()))
                + "  "
                + ticksHuman(
                    line.get("ticks")
                        .getAsLong());
            switch (type) {
                case "quest_complete" -> sb.append(when)
                    .append("  QUEST      ")
                    .append(
                        plain(
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
                        plain(
                            data.get("displayName")
                                .getAsString()))
                    .append(" (")
                    .append(
                        data.get("source")
                            .getAsString())
                    .append(")\n");
                default -> {}
            }
        }

        sb.append("\n== FLAGS FOR REVIEW ==\n");
        flagCount(sb, counts, "nei_cheat", "NEI cheat actions");
        flagCount(sb, counts, "nei_packet", "NEI cheat-channel packets");
        flagCount(sb, counts, "gamemode_change", "gamemode changes");
        flagCount(sb, counts, "gt_explosion", "GT machine explosions");
        flagCount(sb, counts, "death", "player deaths");
        for (JsonObject line : lines) {
            final String type = line.get("t")
                .getAsString();
            if (type.equals("gamemode_change") || type.equals("nei_cheat")) {
                sb.append("  ")
                    .append(
                        fmt.format(
                            new Date(
                                line.get("wall")
                                    .getAsLong())))
                    .append("  ")
                    .append(type)
                    .append("  ")
                    .append(JsonUtil.GSON.toJson(line.getAsJsonObject("data")))
                    .append('\n');
            }
        }

        sb.append("\n== EVENT COUNTS ==\n");
        counts.entrySet()
            .stream()
            .sorted(Map.Entry.comparingByKey())
            .forEach(e -> sb.append(String.format("%-24s %d%n", e.getKey(), e.getValue())));

        sb.append("\nRead VERIFIERS.md in the mod repository for how to independently re-verify this bundle.\n");
        return sb.toString();
    }

    private static void flagCount(StringBuilder sb, Map<String, Integer> counts, String key, String label) {
        sb.append(String.format("%-28s %d%n", label + ":", counts.getOrDefault(key, 0)));
    }

    private static String ticksHuman(long ticks) {
        final long seconds = ticks / 20;
        return String.format("[%dh%02dm%02ds igt]", seconds / 3600, seconds % 3600 / 60, seconds % 60);
    }

    private static String msHuman(long ms) {
        final long seconds = ms / 1000;
        if (seconds >= 3600) {
            return String.format("%dh%02dm%02ds", seconds / 3600, seconds % 3600 / 60, seconds % 60);
        }
        return String.format("%dm%02ds", seconds / 60, seconds % 60);
    }

    /** Old logs may carry §-codes; render-time stripping keeps SUMMARY clean regardless of log vintage. */
    private static String plain(String s) {
        return s == null ? null : s.replaceAll("§.", "");
    }

    // ------------------------------------------------------------------ zip plumbing

    private static void put(ZipOutputStream zip, String name, byte[] bytes) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(bytes);
        zip.closeEntry();
    }

    private static void addDir(ZipOutputStream zip, File dir, String prefix) throws IOException {
        final File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        for (File f : files) {
            if (f.isFile()) {
                put(zip, prefix + f.getName(), Files.readAllBytes(f.toPath()));
            }
        }
    }
}
