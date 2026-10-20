package com.gtnhspeedrun.audit.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.gtnhspeedrun.audit.api.AuditEvents;

/**
 * Test that a listener of another mod can neither change a log line nor stop the line from being logged, and that
 * only the listeners with history get the history.
 */
class AuditEventsTest {

    private static final long DRAIN_TIMEOUT_MS = 10_000;

    @Test
    void listenerEditToItsCopyNeverReachesTheLog(@TempDir Path dir) throws IOException {
        AuditEvents.subscribe(Set.of("test_edit"), (type, data, igtTicks) -> {
            data.addProperty("added", true);
            data.getAsJsonObject("nested")
                .addProperty("dim", -1);
            data.getAsJsonArray("list")
                .add(new JsonParser().parse("99"));
        });
        final JsonObject logged = readOnlyLine(dir, "test_edit", sampleData());

        assertEquals(sampleData(), logged);
    }

    @Test
    void throwingListenerStillLetsTheLineBeLogged(@TempDir Path dir) throws IOException {
        AuditEvents.subscribe(
            Set.of("test_throw"),
            (type, data, igtTicks) -> { throw new IllegalStateException("listener failure"); });
        final JsonObject logged = readOnlyLine(dir, "test_throw", sampleData());

        assertEquals(sampleData(), logged);
    }

    @Test
    void listenerGetsOnlyItsTypesWithTheTicksOfTheLine(@TempDir Path dir) throws IOException {
        final long IGT_TICKS = 1234;
        final List<String> received = new ArrayList<>();
        AuditEvents.subscribe(Set.of("test_wanted"), (type, data, igtTicks) -> received.add(type + "@" + igtTicks));
        final LogWriter writer = startWriter(dir);
        try {
            final AuditLogger logger = new AuditLogger(writer, new TickClock(IGT_TICKS, 0));
            logger.log("test_unwanted", sampleData());
            logger.log("test_wanted", sampleData());
            assertTrue(writer.drain(DRAIN_TIMEOUT_MS));
        } finally {
            writer.close(DRAIN_TIMEOUT_MS);
        }

        assertEquals(List.of("test_wanted@" + IGT_TICKS), received);
    }

    @Test
    void historyGoesOnlyToListenersWithHistoryAndLiveEventsGoToBoth() {
        final List<String> withHistory = new ArrayList<>();
        final List<String> liveOnly = new ArrayList<>();
        AuditEvents
            .subscribeWithHistory(Set.of("test_history"), (type, data, igtTicks) -> withHistory.add("igt" + igtTicks));
        AuditEvents.subscribe(Set.of("test_history"), (type, data, igtTicks) -> liveOnly.add("igt" + igtTicks));

        AuditEvents.publishHistory("test_history", sampleData(), 1);
        AuditEvents.publish("test_history", sampleData(), 2);

        assertEquals(List.of("igt1", "igt2"), withHistory);
        assertEquals(List.of("igt2"), liveOnly);
    }

    @Test
    void historyTypesAreTheTypesOfListenersWithHistory() {
        AuditEvents.subscribe(Set.of("test_live_type"), (type, data, igtTicks) -> {});
        AuditEvents.subscribeWithHistory(Set.of("test_history_type"), (type, data, igtTicks) -> {});

        assertTrue(
            AuditEvents.readHistoryTypes()
                .contains("test_history_type"));
        assertFalse(
            AuditEvents.readHistoryTypes()
                .contains("test_live_type"));
    }

    @Test
    void throwingHistoryListenerStillLetsTheNextListenerGetTheEvent() {
        final List<String> received = new ArrayList<>();
        AuditEvents.subscribeWithHistory(
            Set.of("test_history_throw"),
            (type, data, igtTicks) -> { throw new IllegalStateException("listener failure"); });
        AuditEvents.subscribeWithHistory(Set.of("test_history_throw"), (type, data, igtTicks) -> received.add(type));

        AuditEvents.publishHistory("test_history_throw", sampleData(), 0);

        assertEquals(List.of("test_history_throw"), received);
    }

    private static JsonObject readOnlyLine(Path dir, String type, JsonObject data) throws IOException {
        final LogWriter writer = startWriter(dir);
        try {
            new AuditLogger(writer, new TickClock(0, 0)).log(type, data);
            assertTrue(writer.drain(DRAIN_TIMEOUT_MS));
        } finally {
            writer.close(DRAIN_TIMEOUT_MS);
        }
        final List<String> lines = Files.readAllLines(dir.resolve("audit-000001.jsonl"), StandardCharsets.UTF_8);
        assertEquals(1, lines.size());
        return new JsonParser().parse(lines.get(0))
            .getAsJsonObject()
            .getAsJsonObject("data");
    }

    private static LogWriter startWriter(Path dir) throws IOException {
        final LogWriter writer = new LogWriter(
            LogManager.getLogger("AuditEventsTest"),
            new LogWriter.LogTarget("test-session", 1, dir.resolve("audit-000001.jsonl"), dir),
            new LogWriter.ChainStart(0, "genesis"));
        writer.start();
        return writer;
    }

    private static JsonObject sampleData() {
        final JsonObject nested = new JsonObject();
        nested.addProperty("dim", 28);
        final JsonArray list = new JsonArray();
        list.add(new JsonParser().parse("1"));
        final JsonObject data = new JsonObject();
        data.addProperty("name", "runner");
        data.add("nested", nested);
        data.add("list", list);
        return data;
    }
}
