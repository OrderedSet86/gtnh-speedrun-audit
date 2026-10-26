package com.gtnhspeedrun.audit.core;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;

import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * The line with the highest seq in the log, and the highest session index of any log file name.
 *
 * @param cutFile the file of that line when the file's last line was cut, such as by a power loss during a write. The
 *                chain then resumes from the line before the cut line. Null when no line was cut
 */
public record ChainTail(int maxIndex, long seq, String hash, String sid, String type, @Nullable String cutFile) {

    /**
     * Return the line with the highest seq over every session file, rollback-suffixed files included. The log
     * directory is the source of truth of the chain, and the chain resumes from the exact bytes of that line. When the
     * last line of a file can't be read, the line before the last line counts. The verifier still flags the cut line.
     */
    public static ChainTail read(Path logDir, Logger log) throws IOException {
        int maxIndex = 0;
        long seq = -1;
        String hash = "";
        String sid = "";
        String type = "";
        String cutFile = null;
        for (Path file : AuditDirs.listEntriesOrEmpty(logDir)) {
            final String fileName = file.getFileName()
                .toString();
            final Matcher name = Verifier.CHAIN_FILE_NAME.matcher(fileName);
            if (!name.matches()) {
                continue;
            }
            maxIndex = Math.max(maxIndex, Integer.parseInt(name.group(1)));
            final List<byte[]> lastLines = readLastLines(file);
            for (int position = 0; position < lastLines.size(); position++) {
                final byte[] line = lastLines.get(position);
                final JsonObject obj = parseLine(line);
                if (obj == null) {
                    log.warn("chain_tail.unparseable_line file={} fromEnd={}", fileName, position);
                    continue;
                }
                final long lineSeq = obj.get("seq")
                    .getAsLong();
                if (lineSeq > seq) {
                    seq = lineSeq;
                    hash = JsonUtil.sha256Hex(line);
                    sid = obj.get("sid")
                        .getAsString();
                    type = obj.get("t")
                        .getAsString();
                    cutFile = position == 0 ? null : fileName;
                }
                break;
            }
        }
        return new ChainTail(maxIndex, seq, hash, sid, type, cutFile);
    }

    /** Return the line as an object with a numeric seq and string sid and t, or null for any other line. */
    @Nullable
    private static JsonObject parseLine(byte[] line) {
        final JsonObject obj;
        try {
            obj = new JsonParser().parse(new String(line, StandardCharsets.UTF_8))
                .getAsJsonObject();
        } catch (RuntimeException e) { // noqa: NAR142, Gson 2.2.4 throws several unchecked types for one bad line
            return null;
        }
        final boolean isChainLine = JsonUtil.hasNumber(obj, "seq") && JsonUtil.hasString(obj, "sid")
            && JsonUtil.hasString(obj, "t");
        return isChainLine ? obj : null;
    }

    /**
     * Return the bytes of the last two non-empty lines of {@code file}, the last line first, with CR and LF bytes
     * skipped. The list has fewer lines for a file with fewer.
     */
    private static List<byte[]> readLastLines(Path file) throws IOException {
        final int LINES = 2;
        final List<byte[]> lines = new ArrayList<>(LINES);
        try (RandomAccessFile access = new RandomAccessFile(file.toFile(), "r")) {
            long end = access.length();
            while (lines.size() < LINES) {
                while (end > 0) {
                    access.seek(end - 1);
                    final int lastByte = access.read();
                    if (!(lastByte == '\n' || lastByte == '\r')) {
                        break;
                    }
                    end--;
                }
                if (end == 0) {
                    break;
                }
                long start = end;
                while (start > 0) {
                    access.seek(start - 1);
                    if (access.read() == '\n') {
                        break;
                    }
                    start--;
                }
                final byte[] bytes = new byte[(int) (end - start)];
                access.seek(start);
                access.readFully(bytes);
                lines.add(bytes);
                end = start;
            }
        }
        return lines;
    }
}
