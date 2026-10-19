package com.gtnhspeedrun.audit.export;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import com.google.gson.JsonArray;
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
            putEntry(
                zip,
                "SUMMARY.txt",
                RunSummary.formatSummary(lines, verify, anchor)
                    .getBytes(StandardCharsets.UTF_8));
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
}
