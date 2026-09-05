package com.gtnhspeedrun.audit.core;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * The full line-by-line chain walk, as run by {@code /audit verify} and the export bundler. The startup path
 * uses the cheaper tail-vs-anchor triage in SessionManager; this one recomputes every hash from genesis.
 *
 * <p>
 * The chain is linear even across rollbacks: a new session always resumes from the log's TAIL (which the
 * restore never rewinds), not from the anchor. So verification is simply: order every line from every session
 * file by seq, then check seq continuity and prev-hash linkage throughout.
 */
public final class Verifier {

    private static final Pattern LOG_NAME = Pattern.compile("audit-\\d{6}(?:-r\\d+)?\\.jsonl");

    public static final class Result {

        public String verdict = "OK";
        public long lines;
        public long firstSeq = -1;
        public long lastSeq = -1;
        public String lastHash = "";
        public final List<String> problems = new ArrayList<>();

        void problem(String verdict, String detail) {
            if (this.verdict.equals("OK")) {
                this.verdict = verdict;
            }
            if (problems.size() < 20) {
                problems.add(detail);
            }
        }
    }

    private Verifier() {}

    /**
     * @param upToSeq ignore lines past this seq (the writer may be mid-append on the live file); -1 = no limit
     */
    public static Result verify(File logDir, String worldAuditUuid, long upToSeq) throws IOException {
        final Result result = new Result();
        final List<File> files = listChainFiles(logDir);
        if (files.isEmpty()) {
            result.verdict = "EMPTY";
            return result;
        }

        final String genesis = JsonUtil.sha256Hex("gtnhspeedrunaudit:genesis:" + worldAuditUuid);
        // Session files never interleave seqs, so ordering files by their first seq orders every line.
        final List<FileLines> ordered = new ArrayList<>();
        for (File f : files) {
            final FileLines fl = read(f);
            if (fl != null) {
                ordered.add(fl);
            }
        }
        ordered.sort(Comparator.comparingLong(fl -> fl.firstSeq));

        String expectPrev = genesis;
        long expectSeq = -1;
        for (FileLines fl : ordered) {
            for (int i = 0; i < fl.lines.size(); i++) {
                final byte[] bytes = fl.lines.get(i);
                final JsonObject obj;
                try {
                    obj = new JsonParser().parse(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
                } catch (RuntimeException e) {
                    final boolean lastLineOfLastFile = fl == ordered.get(ordered.size() - 1)
                        && i == fl.lines.size() - 1;
                    if (!lastLineOfLastFile) {
                        result.problem("CORRUPT_LINE", fl.file.getName() + " line " + (i + 1) + ": unparseable");
                    }
                    continue;
                }
                final long seq = obj.get("seq").getAsLong();
                if (upToSeq >= 0 && seq > upToSeq) {
                    continue;
                }
                if (result.firstSeq < 0) {
                    result.firstSeq = seq;
                }
                if (expectSeq >= 0 && seq != expectSeq) {
                    result.problem("SEQ_GAP", "expected seq " + expectSeq + ", found " + seq + " in "
                        + fl.file.getName());
                }
                final String prev = obj.get("prev").getAsString();
                if (!prev.equals(expectPrev)) {
                    result.problem(seq == 0 ? "BAD_GENESIS" : "CHAIN_BREAK",
                        "seq " + seq + " in " + fl.file.getName() + ": prev-hash mismatch");
                }
                expectPrev = JsonUtil.sha256Hex(bytes);
                expectSeq = seq + 1;
                result.lastSeq = seq;
                result.lastHash = expectPrev;
                result.lines++;
            }
        }
        return result;
    }

    private static List<File> listChainFiles(File logDir) {
        final List<File> files = new ArrayList<>();
        final File[] all = logDir.listFiles();
        if (all != null) {
            for (File f : all) {
                if (LOG_NAME.matcher(f.getName()).matches()) {
                    files.add(f);
                }
            }
        }
        return files;
    }

    private static final class FileLines {

        final File file;
        final List<byte[]> lines;
        final long firstSeq;

        FileLines(File file, List<byte[]> lines, long firstSeq) {
            this.file = file;
            this.lines = lines;
            this.firstSeq = firstSeq;
        }
    }

    private static FileLines read(File f) throws IOException {
        final byte[] all = Files.readAllBytes(f.toPath());
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
            firstSeq = new JsonParser().parse(new String(lines.get(0), StandardCharsets.UTF_8)).getAsJsonObject()
                .get("seq").getAsLong();
        } catch (RuntimeException ignored) {
            // Unparseable first line sorts last and gets flagged during the walk.
        }
        return new FileLines(f, lines, firstSeq);
    }
}
