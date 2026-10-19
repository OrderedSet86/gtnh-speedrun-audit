package com.gtnhspeedrun.audit.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

/**
 * Walk the whole chain line by line and recompute every hash from genesis.
 *
 * <p>
 * A new session resumes the chain from the tail of the log, and a backup restore never rewinds the log. The chain is
 * therefore linear across rollbacks. {@link #verify} orders the session files by the seq of their first line, then
 * checks seq continuity and the prev hash of every line.
 */
public final class Verifier {

    // matches:
    // `audit-000007.jsonl`
    // `audit-000007-r2.jsonl`
    // `audit-000007-r2-p3.jsonl`
    // group 1 is the session index
    public static final Pattern CHAIN_FILE_NAME = Pattern.compile("audit-(\\d{6})(?:-r(\\d+))?(?:-p(\\d+))?\\.jsonl");

    private Verifier() {}

    /**
     * Verify every chain file in {@code logDir}. With no chain file, the verdict is EMPTY.
     *
     * @param upToSeq the last seq to check, or -1 for no limit. The writer can be appending past this seq to the live
     *                file.
     */
    public static Result verify(Path logDir, String worldAuditUuid, long upToSeq) throws IOException {
        final List<Path> files = listChainFiles(logDir);
        if (files.isEmpty()) {
            return new Result("EMPTY", 0, -1, -1, "", List.of());
        }
        final Walk result = new Walk();

        final String genesis = genesisHash(worldAuditUuid);
        // the seqs of two session files never interleave, so ordering the files by first seq orders every line
        final List<FileLines> ordered = new ArrayList<>();
        for (Path file : files) {
            final FileLines fileLines = readLines(file);
            if (fileLines != null) {
                ordered.add(fileLines);
            }
        }
        ordered.sort(Comparator.comparingLong(FileLines::firstSeq));

        String expectPrev = genesis;
        long expectSeq = -1;
        for (FileLines fileLines : ordered) {
            final String fileName = fileLines.file()
                .getFileName()
                .toString();
            final List<byte[]> lines = fileLines.lines();
            for (int i = 0; i < lines.size(); i++) {
                final byte[] bytes = lines.get(i);
                final JsonObject obj;
                try {
                    obj = new JsonParser().parse(new String(bytes, StandardCharsets.UTF_8))
                        .getAsJsonObject();
                } catch (JsonParseException | IllegalStateException e) {
                    final boolean lastLineOfLastFile = fileLines == ordered.get(ordered.size() - 1)
                        && i == lines.size() - 1;
                    if (!lastLineOfLastFile) {
                        result.problem("CORRUPT_LINE", fileName + " line " + (i + 1) + ": unparseable");
                    }
                    continue;
                }
                if (!(JsonUtil.hasNumber(obj, "seq") && JsonUtil.hasString(obj, "prev"))) {
                    result.problem(
                        "CORRUPT_LINE",
                        fileName + " line "
                            + (i + 1)
                            + ": no numeric seq or no string prev (expected a LogWriter line)");
                    continue;
                }
                final long seq = obj.get("seq")
                    .getAsLong();
                if (upToSeq >= 0 && seq > upToSeq) {
                    continue;
                }
                if (result.firstSeq < 0) {
                    result.firstSeq = seq;
                }
                if (expectSeq >= 0 && seq != expectSeq) {
                    result.problem("SEQ_GAP", "expected seq " + expectSeq + ", found " + seq + " in " + fileName);
                }
                final String prev = obj.get("prev")
                    .getAsString();
                if (!prev.equals(expectPrev)) {
                    result.problem(
                        seq == 0 ? "BAD_GENESIS" : "CHAIN_BREAK",
                        "seq " + seq + " in " + fileName + ": prev-hash mismatch");
                }
                expectPrev = JsonUtil.sha256Hex(bytes);
                expectSeq = seq + 1;
                result.lastSeq = seq;
                result.lastHash = expectPrev;
                result.lines++;
            }
        }
        return result.toResult();
    }

    private static List<Path> listChainFiles(Path logDir) {
        final List<Path> files = new ArrayList<>();
        for (Path file : AuditDirs.listEntriesOrEmpty(logDir)) {
            if (CHAIN_FILE_NAME.matcher(
                file.getFileName()
                    .toString())
                .matches()) {
                files.add(file);
            }
        }
        return files;
    }

    /** The prev hash of the first line of the chain of a world. */
    public static String genesisHash(String worldAuditUuid) {
        return JsonUtil.sha256Hex("gtnhspeedrunaudit:genesis:" + worldAuditUuid);
    }

    /**
     * Split {@code file} into its non-empty lines, or return null when the file has no non-empty line. A file whose
     * first line has no readable seq is ordered last.
     */
    private static FileLines readLines(Path file) throws IOException {
        final byte[] all = Files.readAllBytes(file);
        final List<byte[]> lines = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < all.length; i++) {
            if (all[i] == '\n') {
                if (i > start) {
                    final byte[] line = new byte[i - start];
                    System.arraycopy(all, start, line, 0, i - start);
                    lines.add(line);
                }
                start = i + 1;
            }
        }
        if (start < all.length) {
            final byte[] line = new byte[all.length - start];
            System.arraycopy(all, start, line, 0, all.length - start);
            lines.add(line);
        }
        if (lines.isEmpty()) {
            return null;
        }
        long firstSeq = Long.MAX_VALUE;
        try {
            final JsonObject first = new JsonParser().parse(new String(lines.get(0), StandardCharsets.UTF_8))
                .getAsJsonObject();
            if (first.has("seq")) {
                firstSeq = first.get("seq")
                    .getAsLong();
            }
        } catch (JsonParseException | IllegalStateException | UnsupportedOperationException | NumberFormatException e) {
            // firstSeq stays Long.MAX_VALUE, so the file is ordered last. verify() reports an unparseable line unless
            // the line ends the log
        }
        return new FileLines(file, lines, firstSeq);
    }

    /**
     * {@code verdict} is the verdict of the first problem, or OK. {@code problems} has the details of the first 20
     * problems only.
     *
     * @param firstSeq -1 when no line was checked
     * @param lastSeq  -1 when no line was checked
     * @param lastHash empty when no line was checked
     */
    public record Result(String verdict, long lines, long firstSeq, long lastSeq, String lastHash,
        List<String> problems) {

        public Result {
            problems = List.copyOf(problems);
        }
    }

    /** The {@link Result} of a walk in progress. */
    private static final class Walk {

        private String verdict = "OK";
        private long lines;
        private long firstSeq = -1;
        private long lastSeq = -1;
        private String lastHash = "";
        private final List<String> problems = new ArrayList<>();

        private void problem(String problemVerdict, String detail) {
            final int MAX_PROBLEMS = 20;
            if (verdict.equals("OK")) {
                verdict = problemVerdict;
            }
            if (problems.size() < MAX_PROBLEMS) {
                problems.add(detail);
            }
        }

        private Result toResult() {
            return new Result(verdict, lines, firstSeq, lastSeq, lastHash, problems);
        }
    }

    private record FileLines(Path file, List<byte[]> lines, long firstSeq) {

        private FileLines {
            lines = List.copyOf(lines);
        }
    }
}
