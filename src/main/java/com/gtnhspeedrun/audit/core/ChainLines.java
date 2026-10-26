package com.gtnhspeedrun.audit.core;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

import javax.annotation.Nullable;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

/** Read the lines of chosen types from the chain files of one log directory, without the hash check. */
public final class ChainLines {

    private ChainLines() {}

    /**
     * Return the lines of {@code types} from every chain file in {@code logDir}, in {@code seq} order. A line of a
     * chosen type that is not valid JSON, or lacks {@code seq}, {@code sidx}, {@code wall}, {@code ticks} or {@code t},
     * is skipped and counted. A crash can cut the last line of a file. A byte that isn't UTF-8 is replaced, and at most
     * the line with the byte is skipped.
     */
    public static Scan readMatchingLines(Path logDir, Set<String> types) throws IOException {
        final List<String> typeNeedles = new ArrayList<>();
        for (String type : types) {
            typeNeedles.add("\"t\":\"" + type + "\"");
        }
        final List<Line> lines = new ArrayList<>();
        int fileCount = 0;
        int skippedLines = 0;
        for (Path file : AuditDirs.listEntriesOrEmpty(logDir)) {
            if (!Verifier.CHAIN_FILE_NAME.matcher(
                file.getFileName()
                    .toString())
                .matches()) {
                continue;
            }
            fileCount++;
            skippedLines += readFileLines(file, typeNeedles, lines);
        }
        lines.sort(Comparator.comparingLong(Line::seq));
        return new Scan(lines, fileCount, skippedLines);
    }

    /** Add the lines of {@code file} that contain one of {@code typeNeedles}, and return the count of bad lines. */
    private static int readFileLines(Path file, List<String> typeNeedles, List<Line> lines) throws IOException {
        int skippedLines = 0;
        // Here an InputStreamReader replaces a byte that isn't UTF-8. The alternative, Files.newBufferedReader(), would
        // throw MalformedInputException on that byte and fail the whole read.
        try (BufferedReader reader = new BufferedReader(
            new InputStreamReader(Files.newInputStream(file), StandardCharsets.UTF_8))) {
            for (String rawLine = reader.readLine(); rawLine != null; rawLine = reader.readLine()) {
                if (!containsAny(rawLine, typeNeedles)) {
                    continue;
                }
                final Line line = parseLine(rawLine);
                if (line == null) {
                    skippedLines++;
                } else {
                    lines.add(line);
                }
            }
        }
        return skippedLines;
    }

    private static boolean containsAny(String rawLine, List<String> needles) {
        for (String needle : needles) {
            if (rawLine.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    /** Parse one log line, or return null for a line that is not JSON or lacks an envelope field. */
    @Nullable
    private static Line parseLine(String rawLine) {
        final JsonElement parsed;
        try {
            parsed = new JsonParser().parse(rawLine);
        } catch (JsonParseException exception) {
            return null;
        }
        if (!(parsed.isJsonObject() && JsonUtil.hasNumber(parsed.getAsJsonObject(), "seq")
            && JsonUtil.hasNumber(parsed.getAsJsonObject(), "sidx")
            && JsonUtil.hasNumber(parsed.getAsJsonObject(), "wall")
            && JsonUtil.hasNumber(parsed.getAsJsonObject(), "ticks")
            && JsonUtil.hasString(parsed.getAsJsonObject(), "t"))) {
            return null;
        }
        final JsonObject line = parsed.getAsJsonObject();
        return new Line(
            line.get("seq")
                .getAsLong(),
            line.get("sidx")
                .getAsInt(),
            line.get("t")
                .getAsString(),
            line.get("wall")
                .getAsLong(),
            line.get("ticks")
                .getAsLong(),
            JsonUtil.hasObject(line, "data") ? line.getAsJsonObject("data") : new JsonObject());
    }

    /**
     * The lines read, with what was read.
     *
     * @param skippedLines the lines of a chosen type that were not valid JSON or lacked an envelope field
     */
    public record Scan(List<Line> lines, int fileCount, int skippedLines) {

        public Scan {
            lines = List.copyOf(lines);
        }
    }

    /** One log line of a chosen type. */
    public record Line(long seq, int sidx, String type, long wallMs, long ticks, JsonObject data) {}
}
