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
import com.gtnhspeedrun.audit.core.ChainFamily;
import com.gtnhspeedrun.audit.core.ForkLink;
import com.gtnhspeedrun.audit.core.JsonUtil;
import com.gtnhspeedrun.audit.core.Verifier;

/**
 * Build the one-file submission bundle: every session log, every snapshot, a new verify report of the whole chain, the
 * anchor values, and a generated SUMMARY.txt. The zip also has every chain linked to this chain by fork links, under
 * {@code chains/<uuid>/}, and chains.json with a check of each fork link. A speedrun.com verifier reads SUMMARY.txt
 * first and opens the JSONL only on suspicion. The export reads files and primitives only, so {@link #writeZip()} can
 * run on a background thread.
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
        final Path auditRoot = dirs.root()
            .getParent();
        final List<JsonObject> lines = readLineageLines(auditRoot);
        final ChainFamily.ChainScan family = ChainFamily.listFamily(auditRoot, anchor.worldAuditUuid());
        final List<ChainReport> reports = checkChains(auditRoot, family, verify);

        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(zipFile))) {
            putEntry(
                zip,
                "SUMMARY.txt",
                RunSummary.formatSummary(new RunSummary.SummaryInput(lines, verify, anchor, reports))
                    .getBytes(StandardCharsets.UTF_8));
            putEntry(zip, "verify-report.json", formatVerifyJson(verify).getBytes(StandardCharsets.UTF_8));
            putEntry(zip, "anchor.json", formatAnchorJson().getBytes(StandardCharsets.UTF_8));
            putEntry(
                zip,
                "chains.json",
                formatChainsJson(reports, family.unreadable()).getBytes(StandardCharsets.UTF_8));
            putDirectory(zip, dirs.log(), "log/");
            putDirectory(zip, dirs.snapshots(), "snapshots/");
            for (ChainReport report : reports) {
                final String uuid = report.chain()
                    .uuid();
                if (uuid.equals(anchor.worldAuditUuid())) {
                    continue;
                }
                final AuditDirs chainDirs = AuditDirs.under(auditRoot.resolve(uuid));
                putDirectory(zip, chainDirs.log(), "chains/" + uuid + "/log/");
                putDirectory(zip, chainDirs.snapshots(), "chains/" + uuid + "/snapshots/");
            }
        }
        return zipFile;
    }

    // ------------------------------------------------------------------ pieces

    /**
     * Return the lines of the run that this world has: each ancestor chain's lines up to the save that the next chain
     * copied, oldest chain first, then this chain's lines.
     */
    private List<JsonObject> readLineageLines(Path auditRoot) throws IOException {
        final ForkLink ownFork = ChainFamily.readForkLink(dirs.log());
        final List<ForkLink> ancestors = ownFork == null ? List.of() : ChainFamily.listAncestors(auditRoot, ownFork);
        final List<JsonObject> lines = new ArrayList<>();
        for (ForkLink ancestor : ancestors) {
            lines.addAll(
                readAllLines(
                    AuditDirs.under(auditRoot.resolve(ancestor.parentUuid()))
                        .log(),
                    ancestor.parentSave()
                        .seq()));
        }
        lines.addAll(readAllLines(dirs.log(), Long.MAX_VALUE));
        return lines;
    }

    /**
     * Parse every line up to {@code upToSeq} of every JSONL file in {@code logDir}, sorted by seq. A line that does
     * not parse, or has no numeric seq, wall or ticks, no string t, or no data object, is skipped.
     */
    private static List<JsonObject> readAllLines(Path logDir, long upToSeq) throws IOException {
        final List<JsonObject> lines = new ArrayList<>();
        for (Path file : AuditDirs.listEntriesOrEmpty(logDir)) {
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
                    if (isChainLine(parsed) && parsed.get("seq")
                        .getAsLong() <= upToSeq) {
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

    /** Verify every chain of {@code family}, and check each fork link against the parent chain. */
    private List<ChainReport> checkChains(Path auditRoot, ChainFamily.ChainScan family, Verifier.Result exportedVerify)
        throws IOException {
        final List<ChainReport> reports = new ArrayList<>();
        for (ChainFamily.FamilyChain chain : family.chains()) {
            final Verifier.Result verify = chain.uuid()
                .equals(anchor.worldAuditUuid())
                    ? exportedVerify
                    : Verifier.verify(
                        AuditDirs.under(auditRoot.resolve(chain.uuid()))
                            .log(),
                        chain.uuid(),
                        -1);
            final ForkLink link = chain.link();
            reports.add(new ChainReport(chain, verify, link != null && isParentLinkIntact(auditRoot, link)));
        }
        return reports;
    }

    /** Return whether the parent chain verifies up to the fork seq, and the line at that seq has the fork hash. */
    private static boolean isParentLinkIntact(Path auditRoot, ForkLink link) throws IOException {
        final Path parentLog = AuditDirs.under(auditRoot.resolve(link.parentUuid()))
            .log();
        if (!Files.isDirectory(parentLog)) {
            return false;
        }
        final long forkSeq = link.parentSave()
            .seq();
        final Verifier.Result parent = Verifier.verify(parentLog, link.parentUuid(), forkSeq);
        return "OK".equals(parent.verdict()) && parent.lastSeq() == forkSeq
            && parent.lastHash()
                .equals(link.parentHash());
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

    /** Build chains.json. The run analyzer reads these keys, so NEVER rename one. */
    private static String formatChainsJson(List<ChainReport> reports, int unreadable) {
        final JsonArray chains = new JsonArray();
        for (ChainReport report : reports) {
            final JsonObject chain = new JsonObject();
            chain.addProperty(
                "uuid",
                report.chain()
                    .uuid());
            if (report.chain()
                .folder() != null) {
                chain.addProperty(
                    "folder",
                    report.chain()
                        .folder());
            }
            if (report.chain()
                .link() != null) {
                chain.add(
                    "fork",
                    report.chain()
                        .link()
                        .toJson());
                chain.addProperty("parentLinkOk", report.parentLinkOk());
            }
            chain.addProperty(
                "verdict",
                report.verify()
                    .verdict());
            chain.addProperty(
                "lines",
                report.verify()
                    .lines());
            chain.addProperty(
                "lastSeq",
                report.verify()
                    .lastSeq());
            chain.addProperty(
                "lastHash",
                report.verify()
                    .lastHash());
            chains.add(chain);
        }
        final JsonObject o = new JsonObject();
        o.add("chains", chains);
        o.addProperty("unreadableChains", unreadable);
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

    /**
     * One chain of the bundle, verified.
     *
     * @param parentLinkOk false for a chain that is no world copy
     */
    public record ChainReport(ChainFamily.FamilyChain chain, Verifier.Result verify, boolean parentLinkOk) {}

    /** The run state at export time, written to anchor.json. */
    public record AnchorView(String worldAuditUuid, long seq, long ticks, long onlineTicks, long timingStartWallMs) {}
}
