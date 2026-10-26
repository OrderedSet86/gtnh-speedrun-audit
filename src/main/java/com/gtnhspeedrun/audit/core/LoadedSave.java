package com.gtnhspeedrun.audit.core;

import javax.annotation.Nullable;

import com.google.gson.JsonObject;

/**
 * The logged save that a world was loaded from: the loadedSave block of session_start.
 *
 * <pre>
 * {"kind":"stop","savedSidx":1,"savedSeq":87,"savedWall":1792949789924,"matches":1}
 * </pre>
 *
 * @param line    the line of the save, or null for {@link SaveKind#UNLOGGED}
 * @param matches the lines of {@code kind} with the loaded anchor values. Saves before timing starts have equal values
 */
public record LoadedSave(SaveKind kind, @Nullable SavedLine line, int matches) {

    /** Build the loadedSave block. The run analyzer reads these keys, so NEVER rename one. */
    public JsonObject toJson() {
        final JsonObject data = new JsonObject();
        data.addProperty("kind", kind.jsonName);
        if (line != null) {
            data.addProperty("savedSidx", line.sidx());
            data.addProperty("savedSeq", line.seq());
            data.addProperty("savedWall", line.wallMs());
        }
        data.addProperty("matches", matches);
        if (line != null && line.backup() != null) {
            data.addProperty(
                "file",
                line.backup()
                    .file());
            data.addProperty(
                "bytes",
                line.backup()
                    .bytes());
            data.addProperty(
                "fileModified",
                line.backup()
                    .modifiedMs());
        }
        return data;
    }

    /** What wrote the save. */
    public enum SaveKind {

        /** A ServerUtilities backup: the save before backup_done. */
        BACKUP("backup"),
        /** An overworld save that world_saved logged. */
        SAVE("save"),
        /** The shutdown save after session_end. */
        STOP("stop"),
        /** No line of the log has the loaded anchor values. */
        UNLOGGED("unlogged");

        private final String jsonName;

        SaveKind(String jsonName) {
            this.jsonName = jsonName;
        }
    }

    /**
     * The line that logged the save.
     *
     * @param backup the backup file, or null unless the line is a backup_done line with a file
     */
    public record SavedLine(int sidx, long seq, long wallMs, @Nullable BackupFile backup) {}

    /** A backup zip, from the fields of backup_done. */
    public record BackupFile(String file, long bytes, long modifiedMs) {}
}
