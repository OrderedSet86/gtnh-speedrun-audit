package com.gtnhspeedrun.audit.core;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.OptionalLong;
import java.util.Set;

import javax.annotation.Nullable;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

/**
 * Read the events of chosen types back from the log of a world, without the events that the world lost. A backup
 * restore or a crash rewinds the world, but never the log. The lines after the world's last saved line are dropped. A
 * milestone reached again after the rewind has a later line, and that line is kept.
 */
public final class LedgerHistory {

    private static final String SESSION_START = "session_start";

    private LedgerHistory() {}

    /**
     * Return the events of {@code types} from every chain file in {@code logDir}, in {@code seq} order.
     *
     * <ul>
     * <li>At each {@code session_start} line with a {@code rollback} block, the events after the world's last line at
     * that start are dropped: {@code seq > logTail.seq - rollback.eventsRewound}.</li>
     * <li>The events after {@code lastWorldSeq}, the world's last line at this start, are dropped too.</li>
     * <li>A line of a chosen type that is not valid JSON is skipped and counted. A crash can cut the last line of a
     * file.</li>
     * </ul>
     */
    public static History read(Path logDir, Set<String> types, long lastWorldSeq) throws IOException {
        final List<String> typeNeedles = new ArrayList<>();
        for (String type : types) {
            typeNeedles.add("\"t\":\"" + type + "\"");
        }
        typeNeedles.add("\"t\":\"" + SESSION_START + "\"");
        final List<LogLine> lines = new ArrayList<>();
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
            skippedLines += readMatchingLines(file, typeNeedles, lines);
        }
        lines.sort(Comparator.comparingLong(LogLine::seq));

        final List<Event> events = new ArrayList<>();
        for (LogLine line : lines) {
            if (SESSION_START.equals(line.type())) {
                final OptionalLong restoredSeq = readRestoredSeq(line.data());
                if (restoredSeq.isPresent()) {
                    events.removeIf(event -> event.seq() > restoredSeq.getAsLong());
                }
            }
            // a session_start line is read for the rollback block. The line is an event only when a caller chose the
            // type
            if (types.contains(line.type())) {
                events.add(new Event(line.seq(), line.type(), line.data(), line.igtTicks()));
            }
        }
        events.removeIf(event -> event.seq() > lastWorldSeq);
        return new History(events, fileCount, skippedLines);
    }

    /** Add the lines of {@code file} that contain one of {@code typeNeedles}, and return the count of bad lines. */
    private static int readMatchingLines(Path file, List<String> typeNeedles, List<LogLine> lines) throws IOException {
        int skippedLines = 0;
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            for (String rawLine = reader.readLine(); rawLine != null; rawLine = reader.readLine()) {
                if (!containsAny(rawLine, typeNeedles)) {
                    continue;
                }
                final LogLine line = parseLine(rawLine);
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

    /**
     * Parse one log line, or return null for a line that is not JSON or lacks {@code seq}, {@code ticks} or {@code t}.
     */
    @Nullable
    private static LogLine parseLine(String rawLine) {
        final JsonElement parsed;
        try {
            parsed = new JsonParser().parse(rawLine);
        } catch (JsonParseException exception) {
            return null;
        }
        if (!(parsed.isJsonObject() && hasNumber(parsed.getAsJsonObject(), "seq")
            && hasNumber(parsed.getAsJsonObject(), "ticks")
            && parsed.getAsJsonObject()
                .has("t"))) {
            return null;
        }
        final JsonObject line = parsed.getAsJsonObject();
        final JsonElement rawData = line.get("data");
        return new LogLine(
            line.get("seq")
                .getAsLong(),
            line.get("t")
                .getAsString(),
            rawData != null && rawData.isJsonObject() ? rawData.getAsJsonObject() : new JsonObject(),
            line.get("ticks")
                .getAsLong());
    }

    private static boolean hasNumber(JsonObject object, String key) {
        final JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive()
            && value.getAsJsonPrimitive()
                .isNumber();
    }

    /** Return the world's last line at the start of {@code sessionStart}, for a start with a rollback block. */
    private static OptionalLong readRestoredSeq(JsonObject sessionStart) {
        final JsonElement rawTail = sessionStart.get("logTail");
        final JsonElement rawRollback = sessionStart.get("rollback");
        if (!(rawTail != null && rawTail.isJsonObject()
            && hasNumber(rawTail.getAsJsonObject(), "seq")
            && rawRollback != null
            && rawRollback.isJsonObject()
            && hasNumber(rawRollback.getAsJsonObject(), "eventsRewound"))) {
            return OptionalLong.empty();
        }
        final long tailSeq = rawTail.getAsJsonObject()
            .get("seq")
            .getAsLong();
        final long eventsRewound = rawRollback.getAsJsonObject()
            .get("eventsRewound")
            .getAsLong();
        return OptionalLong.of(tailSeq - eventsRewound);
    }

    /**
     * The surviving events, with what was read.
     *
     * @param skippedLines the lines of a chosen type that were not valid JSON
     */
    public record History(List<Event> events, int fileCount, int skippedLines) {

        public History {
            events = List.copyOf(events);
        }
    }

    /** One log line of a chosen type. */
    public record Event(long seq, String type, JsonObject data, long igtTicks) {}

    private record LogLine(long seq, String type, JsonObject data, long igtTicks) {}
}
