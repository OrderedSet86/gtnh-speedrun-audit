package com.gtnhspeedrun.audit.compat;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.google.gson.JsonObject;
import com.gtnhspeedrun.audit.core.AuditDirs;

import serverutils.task.backup.BackupTask;

/**
 * Read the ServerUtilities backup state. The serverutils classes exist only when ServerUtilities is installed, so
 * every reference to {@code BackupTask} is in the nested {@code Holder} class, behind the
 * {@code Compat.SERVER_UTILITIES} guard.
 */
public final class BackupAccess {

    private BackupAccess() {}

    /**
     * Return whether a ServerUtilities backup is running, or null without ServerUtilities or when the ServerUtilities
     * API doesn't match.
     */
    public static Boolean isBackupRunning() {
        if (!Compat.SERVER_UTILITIES.isLoaded()) {
            return null;
        }
        try {
            return Holder.isBackupRunning();
        } catch (NoClassDefFoundError | NoSuchMethodError | RuntimeException e) { // noqa: NAR142, degrade and report
                                                                                  // over ServerUtilities code
            return null;
        }
    }

    /**
     * Add the name, size and modification time of the newest regular file in the ServerUtilities backup folder. After
     * a backup finishes, the newest file is the file of that backup. Add {@code folderEmpty} instead when the folder
     * has no regular file, is not a directory or can't be read, and {@code folderUnavailable} when the
     * ServerUtilities API doesn't match. A size or a time that can't be read counts as 0.
     */
    public static void describeNewest(JsonObject into) {
        try {
            final Path folder = Holder.readBackupFolder()
                .toPath();
            Path newest = null;
            for (Path entry : AuditDirs.listEntriesOrEmpty(folder)) {
                if (Files.isRegularFile(entry)
                    && (newest == null || readModifiedMillis(entry) > readModifiedMillis(newest))) {
                    newest = entry;
                }
            }
            if (newest == null) {
                into.addProperty("folderEmpty", true);
                return;
            }
            into.addProperty(
                "file",
                newest.getFileName()
                    .toString());
            into.addProperty("bytes", readSize(newest));
            into.addProperty("lastModified", readModifiedMillis(newest));
        } catch (NoClassDefFoundError | NoSuchFieldError | RuntimeException e) { // noqa: NAR142, degrade and report
                                                                                 // over ServerUtilities code
            into.addProperty("folderUnavailable", String.valueOf(e));
        }
    }

    private static long readModifiedMillis(Path file) {
        try {
            return Files.getLastModifiedTime(file)
                .toMillis();
        } catch (IOException e) {
            return 0;
        }
    }

    private static long readSize(Path file) {
        try {
            return Files.size(file);
        } catch (IOException e) {
            return 0;
        }
    }

    private static final class Holder {

        private static boolean isBackupRunning() {
            return BackupTask.isBackupRunning();
        }

        private static File readBackupFolder() {
            return BackupTask.BACKUP_FOLDER;
        }
    }
}
