package com.gtnhspeedrun.audit.core;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

import com.google.gson.JsonObject;

/**
 * Find the logged save that a world was loaded from, by the anchor values that the save wrote. A backup outranks a
 * world_saved line, and a world_saved line outranks a stop. Within one kind, the latest line wins. Rewound lines
 * count: a backup from discarded play can be loaded.
 */
public final class LoadedSaveLookup {

    private static final Set<String> SAVE_LINE_TYPES = Set
        .of("world_saving", "backup_done", "world_saved", "session_end");

    private LoadedSaveLookup() {}

    public static LoadedSave findLoadedSave(Path logDir, AnchorValues loaded) throws IOException {
        final ChainLines.Scan scan = ChainLines.readMatchingLines(logDir, SAVE_LINE_TYPES);
        // backup_done lines from before backup_done.save: the lastSave of the session's latest world_saving off line
        final Map<Integer, AnchorValues> offSaveBySession = new HashMap<>();
        LoadedSave best = new LoadedSave(LoadedSave.SaveKind.UNLOGGED, null, 0);
        for (ChainLines.Line line : scan.lines()) {
            final LoadedSave match = matchLine(line, loaded, offSaveBySession);
            if (match == null) {
                continue;
            }
            final int rankChange = rankSaveKind(match.kind()) - rankSaveKind(best.kind());
            if (rankChange > 0) {
                best = match;
            } else if (rankChange == 0) {
                best = new LoadedSave(match.kind(), match.line(), best.matches() + 1);
            }
        }
        return best;
    }

    /** Return the save that {@code line} logged when the save has the {@code loaded} values, else null. */
    @Nullable
    private static LoadedSave matchLine(ChainLines.Line line, AnchorValues loaded,
        Map<Integer, AnchorValues> offSaveBySession) {
        final JsonObject data = line.data();
        final LoadedSave.SavedLine savedLine = new LoadedSave.SavedLine(line.sidx(), line.seq(), line.wallMs(), null);
        switch (line.type()) {
            case "world_saving" -> {
                final AnchorValues lastSave = AnchorValues.parseAnchorValues(data.get("lastSave"));
                if (lastSave != null) {
                    offSaveBySession.put(line.sidx(), lastSave);
                }
                return null;
            }
            case "backup_done" -> {
                final AnchorValues save = AnchorValues.parseAnchorValues(data.get("save"));
                final AnchorValues backedUp = save != null ? save : offSaveBySession.get(line.sidx());
                final LoadedSave.SavedLine backupLine = new LoadedSave.SavedLine(
                    line.sidx(),
                    line.seq(),
                    line.wallMs(),
                    readBackupFile(data));
                return loaded.equals(backedUp) ? new LoadedSave(LoadedSave.SaveKind.BACKUP, backupLine, 1) : null;
            }
            case "world_saved" -> {
                return loaded.equals(AnchorValues.parseAnchorValues(data.get("anchor")))
                    ? new LoadedSave(LoadedSave.SaveKind.SAVE, savedLine, 1)
                    : null;
            }
            case "session_end" -> {
                // the stop save writes the seq of session_end, and the clocks don't advance after session_end
                return line.seq() == loaded.seq() && line.ticks() == loaded.ticks()
                    ? new LoadedSave(LoadedSave.SaveKind.STOP, savedLine, 1)
                    : null;
            }
            default -> {
                return null;
            }
        }
    }

    private static int rankSaveKind(LoadedSave.SaveKind kind) {
        return switch (kind) {
            case BACKUP -> 3;
            case SAVE -> 2;
            case STOP -> 1;
            case UNLOGGED -> 0;
        };
    }

    /** Return the backup file of a backup_done line, or null when the line has no file. */
    @Nullable
    private static LoadedSave.BackupFile readBackupFile(JsonObject backupDone) {
        if (!(JsonUtil.hasString(backupDone, "file") && JsonUtil.hasNumber(backupDone, "bytes")
            && JsonUtil.hasNumber(backupDone, "lastModified"))) {
            return null;
        }
        return new LoadedSave.BackupFile(
            backupDone.get("file")
                .getAsString(),
            backupDone.get("bytes")
                .getAsLong(),
            backupDone.get("lastModified")
                .getAsLong());
    }
}
