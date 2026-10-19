package com.gtnhspeedrun.audit.core;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.regex.Matcher;

import org.apache.logging.log4j.Logger;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** The line with the highest seq in the log, and the highest session index of any log file name. */
public record ChainTail(int maxIndex, long seq, String hash, String sid, String type) {

    /**
     * Return the line with the highest seq over every session file, rollback-suffixed files included. The log
     * directory is the source of truth of the chain, and the chain resumes from the exact bytes of that line.
     */
    public static ChainTail read(Path logDir, Logger log) throws IOException {
        int maxIndex = 0;
        long seq = -1;
        String hash = "";
        String sid = "";
        String type = "";
        for (Path file : AuditDirs.listEntriesOrEmpty(logDir)) {
            final String fileName = file.getFileName()
                .toString();
            final Matcher name = Verifier.CHAIN_FILE_NAME.matcher(fileName);
            if (!name.matches()) {
                continue;
            }
            maxIndex = Math.max(maxIndex, Integer.parseInt(name.group(1)));
            final byte[] lastLine = readLastLine(file);
            if (lastLine == null) {
                continue;
            }
            try {
                final JsonObject obj = new JsonParser().parse(new String(lastLine, StandardCharsets.UTF_8))
                    .getAsJsonObject();
                final long lineSeq = obj.get("seq")
                    .getAsLong();
                if (lineSeq > seq) {
                    seq = lineSeq;
                    hash = JsonUtil.sha256Hex(lastLine);
                    sid = obj.get("sid")
                        .getAsString();
                    type = obj.get("t")
                        .getAsString();
                }
            } catch (RuntimeException e) {
                log.warn("Unparseable tail line in {} — verifier will flag it", fileName);
            }
        }
        return new ChainTail(maxIndex, seq, hash, sid, type);
    }

    /**
     * Return the bytes of the last non-empty line of {@code file}, with trailing CR and LF bytes skipped, or null for a
     * file with no such line.
     */
    private static byte[] readLastLine(Path file) throws IOException {
        try (RandomAccessFile access = new RandomAccessFile(file.toFile(), "r")) {
            long end = access.length();
            while (end > 0) {
                access.seek(end - 1);
                final int lastByte = access.read();
                if (lastByte == '\n' || lastByte == '\r') {
                    end--;
                } else {
                    break;
                }
            }
            if (end == 0) {
                return null;
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
            return bytes;
        }
    }
}
