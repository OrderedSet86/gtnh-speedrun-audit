package com.gtnhspeedrun.audit.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import javax.annotation.Nullable;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.JsonObject;

/** Test that findLoadedSave() returns the line that logged the save with the loaded anchor values. */
class LoadedSaveLookupTest {

    @Test
    void everyLoggedSaveIsFoundByItsAnchorValues(@TempDir Path dir) throws IOException {
        final int CASES = 60;
        final Random random = new Random(26);
        for (int run = 0; run < CASES; run++) {
            final Path logDir = Files.createDirectory(dir.resolve("run" + run));
            final GeneratedLog generated = writeGeneratedLog(logDir, random);
            for (GeneratedSave save : generated.saves()) {
                final LoadedSave found = LoadedSaveLookup.findLoadedSave(logDir, save.values());

                assertEquals(save.kind(), found.kind(), "run " + run + " " + save);
                assertNotNull(found.line());
                assertEquals(
                    save.seq(),
                    found.line()
                        .seq(),
                    "run " + run + " " + save);
                assertEquals(1, found.matches());
                if (save.kind() == LoadedSave.SaveKind.BACKUP) {
                    assertEquals(
                        save.backupFile(),
                        found.line()
                            .backup()
                            .file());
                }
            }
            final AnchorValues neverSaved = new AnchorValues(-7, -7, -7);
            assertEquals(
                LoadedSave.SaveKind.UNLOGGED,
                LoadedSaveLookup.findLoadedSave(logDir, neverSaved)
                    .kind());
        }
    }

    @Test
    void noLogGivesUnloggedWithNoLine(@TempDir Path dir) throws IOException {
        final LoadedSave found = LoadedSaveLookup.findLoadedSave(dir.resolve("log"), new AnchorValues(4, 80, 1000));

        assertEquals(LoadedSave.SaveKind.UNLOGGED, found.kind());
        assertNull(found.line());
        assertEquals(0, found.matches());
    }

    @Test
    void equalValuesBeforeTimingStartsCountEveryMatchAndNameTheLatest(@TempDir Path logDir) throws IOException {
        final AnchorValues untimed = new AnchorValues(2, 0, 0);
        final List<String> lines = new ArrayList<>();
        for (long seq = 3; seq < 8; seq++) {
            lines.add(formatLine(seq, 1, "world_saved", wrapAnchor("anchor", untimed)));
        }
        Files.write(logDir.resolve("audit-000001.jsonl"), lines, StandardCharsets.UTF_8);

        final LoadedSave found = LoadedSaveLookup.findLoadedSave(logDir, untimed);

        assertEquals(LoadedSave.SaveKind.SAVE, found.kind());
        assertEquals(5, found.matches());
        assertEquals(
            7,
            found.line()
                .seq());
    }

    @Test
    void backupFromBeforeBackupDoneSaveIsMatchedByTheSessionsOffLine(@TempDir Path logDir) throws IOException {
        final AnchorValues backedUp = new AnchorValues(10, 400, 5000);
        final JsonObject off = wrapAnchor("lastSave", backedUp);
        off.addProperty("enabled", false);
        final JsonObject done = formatBackupDone("2026-10-15-23-22-49.zip");
        Files.write(
            logDir.resolve("audit-000003.jsonl"),
            List.of(formatLine(11, 3, "world_saving", off), formatLine(12, 3, "backup_done", done)),
            StandardCharsets.UTF_8);

        final LoadedSave found = LoadedSaveLookup.findLoadedSave(logDir, backedUp);

        assertEquals(LoadedSave.SaveKind.BACKUP, found.kind());
        assertEquals(
            12,
            found.line()
                .seq());
        assertEquals(
            "2026-10-15-23-22-49.zip",
            found.line()
                .backup()
                .file());
    }

    @Test
    void offLineOfAnotherSessionDoesNotNameABackup(@TempDir Path logDir) throws IOException {
        final AnchorValues saved = new AnchorValues(10, 400, 5000);
        final JsonObject off = wrapAnchor("lastSave", saved);
        off.addProperty("enabled", false);
        Files.write(
            logDir.resolve("audit-000003.jsonl"),
            List.of(
                formatLine(11, 3, "world_saving", off),
                formatLine(12, 4, "backup_done", formatBackupDone("other.zip"))),
            StandardCharsets.UTF_8);

        assertEquals(
            LoadedSave.SaveKind.UNLOGGED,
            LoadedSaveLookup.findLoadedSave(logDir, saved)
                .kind());
    }

    @Test
    void backupWithNoFileIsABackupWithNoBackupFile(@TempDir Path logDir) throws IOException {
        final AnchorValues backedUp = new AnchorValues(10, 400, 5000);
        final JsonObject done = wrapAnchor("save", backedUp);
        done.addProperty("folderEmpty", true);
        Files.write(
            logDir.resolve("audit-000001.jsonl"),
            List.of(formatLine(11, 1, "backup_done", done)),
            StandardCharsets.UTF_8);

        final LoadedSave found = LoadedSaveLookup.findLoadedSave(logDir, backedUp);

        assertEquals(LoadedSave.SaveKind.BACKUP, found.kind());
        assertNull(
            found.line()
                .backup());
    }

    /**
     * Write a log of several sessions. Each session has autosaves and backups, and ends with a stop. No two saves have
     * equal values: the ticks only grow.
     */
    private static GeneratedLog writeGeneratedLog(Path logDir, Random random) throws IOException {
        final List<GeneratedSave> saves = new ArrayList<>();
        long seq = 0;
        long ticks = 0;
        final int sessions = 1 + random.nextInt(4);
        for (int sidx = 1; sidx <= sessions; sidx++) {
            final List<String> lines = new ArrayList<>();
            lines.add(formatLine(seq++, sidx, "session_start", new JsonObject()));
            final int saveCount = random.nextInt(6);
            for (int i = 0; i < saveCount; i++) {
                ticks += 1 + random.nextInt(900);
                lines.add(formatLine(seq++, sidx, "dim_first_visit", new JsonObject()));
                final AnchorValues values = new AnchorValues(seq - 1, ticks, ticks * 50);
                lines.add(formatLine(seq++, sidx, "world_saved", wrapAnchor("anchor", values)));
                if (random.nextBoolean()) {
                    final JsonObject off = wrapAnchor("lastSave", values);
                    off.addProperty("enabled", false);
                    lines.add(formatLine(seq++, sidx, "world_saving", off));
                    final String file = "backup-" + sidx + "-" + i + ".zip";
                    final JsonObject done = formatBackupDone(file);
                    done.add("save", values.toJson());
                    saves.add(new GeneratedSave(values, LoadedSave.SaveKind.BACKUP, seq, file));
                    lines.add(formatLine(seq++, sidx, "backup_done", done));
                } else {
                    saves.add(new GeneratedSave(values, LoadedSave.SaveKind.SAVE, seq - 1, null));
                }
            }
            ticks += 1 + random.nextInt(900);
            saves.add(new GeneratedSave(new AnchorValues(seq, ticks, 0), LoadedSave.SaveKind.STOP, seq, null));
            lines.add(formatLine(seq++, sidx, "session_end", new JsonObject(), ticks));
            Files.write(logDir.resolve(String.format("audit-%06d.jsonl", sidx)), lines, StandardCharsets.UTF_8);
        }
        return new GeneratedLog(saves);
    }

    private static JsonObject wrapAnchor(String key, AnchorValues values) {
        final JsonObject data = new JsonObject();
        data.add(key, values.toJson());
        return data;
    }

    private static JsonObject formatBackupDone(String file) {
        final JsonObject done = new JsonObject();
        done.addProperty("file", file);
        done.addProperty("bytes", 1234);
        done.addProperty("lastModified", 99);
        return done;
    }

    private static String formatLine(long seq, int sidx, String type, JsonObject data) {
        return formatLine(seq, sidx, type, data, 0);
    }

    private static String formatLine(long seq, int sidx, String type, JsonObject data, long ticks) {
        final JsonObject line = new JsonObject();
        line.addProperty("v", 1);
        line.addProperty("seq", seq);
        line.addProperty("sid", "session-" + sidx);
        line.addProperty("sidx", sidx);
        line.addProperty("t", type);
        line.addProperty("wall", seq * 1000);
        line.addProperty("ticks", ticks);
        line.addProperty("pticks", ticks);
        line.addProperty("thread", "Server thread");
        line.add("data", data);
        line.addProperty("prev", "0");
        return JsonUtil.GSON.toJson(line);
    }

    private record GeneratedLog(List<GeneratedSave> saves) {}

    private record GeneratedSave(AnchorValues values, LoadedSave.SaveKind kind, long seq,
        @Nullable String backupFile) {}
}
