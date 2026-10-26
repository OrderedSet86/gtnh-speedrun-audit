package com.gtnhspeedrun.audit.core;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.OptionalLong;
import java.util.Set;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

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
     * <li>A line of a chosen type that is not valid JSON, or lacks an envelope field, is skipped and counted. A crash
     * can cut the last line of a file. A byte that isn't UTF-8 is replaced, and at most the line with the byte is
     * skipped.</li>
     * </ul>
     */
    public static History read(Path logDir, Set<String> types, long lastWorldSeq) throws IOException {
        final Set<String> readTypes = new HashSet<>(types);
        readTypes.add(SESSION_START);
        final ChainLines.Scan scan = ChainLines.readMatchingLines(logDir, readTypes);

        final List<Event> events = new ArrayList<>();
        for (ChainLines.Line line : scan.lines()) {
            if (SESSION_START.equals(line.type())) {
                final OptionalLong restoredSeq = readRestoredSeq(line.data());
                if (restoredSeq.isPresent()) {
                    events.removeIf(event -> event.seq() > restoredSeq.getAsLong());
                }
            }
            // a session_start line is read for the rollback block. The line is an event only when a caller chose the
            // type
            if (types.contains(line.type())) {
                events.add(new Event(line.seq(), line.type(), line.data(), line.ticks()));
            }
        }
        events.removeIf(event -> event.seq() > lastWorldSeq);
        return new History(events, scan.fileCount(), scan.skippedLines());
    }

    /** Return the world's last line at the start of {@code sessionStart}, for a start with a rollback block. */
    private static OptionalLong readRestoredSeq(JsonObject sessionStart) {
        final JsonElement rawTail = sessionStart.get("logTail");
        final JsonElement rawRollback = sessionStart.get("rollback");
        if (!(rawTail != null && rawTail.isJsonObject()
            && JsonUtil.hasNumber(rawTail.getAsJsonObject(), "seq")
            && rawRollback != null
            && rawRollback.isJsonObject()
            && JsonUtil.hasNumber(rawRollback.getAsJsonObject(), "eventsRewound"))) {
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
     * @param skippedLines the lines of a chosen type that were not valid JSON or lacked an envelope field
     */
    public record History(List<Event> events, int fileCount, int skippedLines) {

        public History {
            events = List.copyOf(events);
        }
    }

    /** One log line of a chosen type. */
    public record Event(long seq, String type, JsonObject data, long igtTicks) {}
}
