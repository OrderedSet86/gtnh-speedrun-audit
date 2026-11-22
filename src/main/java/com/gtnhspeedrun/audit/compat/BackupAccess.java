package com.gtnhspeedrun.audit.compat;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;

import com.google.gson.JsonObject;
import com.gtnhspeedrun.audit.core.AuditDirs;

import serverutils.task.backup.BackupTask;

/**
 * Read the ServerUtilities backup state. ServerUtilities has two backup APIs: {@code BackupTask} up to 2.4 (packs 2.8.4
 * and 2.9.0), and {@code BackupCoordinator} with {@code BackupRoutine} from 2.5 (pack 2.9.1). The mod compiles against
 * 2.4, so {@link CoordinatorApi} calls the 2.5 API through reflection. Every reference to {@code BackupTask} is in
 * {@link ThreadApi}. When ServerUtilities isn't installed, a call into {@link ThreadApi} raises a {@link LinkageError},
 * {@link #isBackupRunning} returns null, and {@link #describeNewest} adds {@code folderUnavailable}.
 */
public final class BackupAccess {

    private BackupAccess() {}

    /**
     * Return whether a ServerUtilities backup is running, or null without ServerUtilities or when no backup API
     * matches.
     */
    public static Boolean isBackupRunning() {
        if (!Compat.SERVER_UTILITIES.isLoaded()) {
            return null;
        }
        try {
            return ApiHolder.API.isRunning();
        } catch (NoClassDefFoundError | NoSuchFieldError | ReflectiveOperationException e) {
            return null;
        }
    }

    /**
     * Add the name, size and modification time of the newest regular file in the ServerUtilities backup folder. After a
     * backup finishes, the newest file is the file of that backup. Add {@code folderEmpty} instead when the folder
     * doesn't have a regular file, is not a directory or can't be read, and {@code folderUnavailable} when no backup
     * API matches. A size or a time that can't be read counts as 0.
     */
    public static void describeNewest(JsonObject into) {
        try {
            final Path folder = ApiHolder.API.readBackupFolder()
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
        } catch (NoClassDefFoundError | NoSuchFieldError | ReflectiveOperationException | InvalidPathException e) {
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

    /** Each implementation reads one generation of the ServerUtilities backup API. */
    private interface BackupApi {

        boolean isRunning() throws ReflectiveOperationException;

        File readBackupFolder() throws ReflectiveOperationException;
    }

    /**
     * {@code ApiHolder} loads at the first read of {@link #API}, so a ServerUtilities class doesn't load before that
     * read.
     */
    private static final class ApiHolder {

        private static final BackupApi API = resolveApi();

        private static BackupApi resolveApi() {
            try {
                return CoordinatorApi.resolve();
            } catch (ReflectiveOperationException | NoClassDefFoundError e) {
                return new ThreadApi();
            }
        }
    }

    /**
     * {@code ThreadApi} reads the backup state of ServerUtilities 2.4 and older. Pack 2.8.4 has 2.2.2, and pack 2.9.0
     * has 2.4.22. When a backup runs on a separate thread, {@code BackupTask.thread} is the thread that writes the
     * archive. When {@code use_separate_thread} is false, the backup runs on the server thread and {@link #isRunning}
     * stays false.
     */
    private static final class ThreadApi implements BackupApi {

        @Override
        public boolean isRunning() {
            // BackupTask.isBackupRunning() is absent in 2.2.2. In 2.4.22, isBackupRunning() is also true while the
            // retention thread deletes old backups after the archive is written, and `WorldSavingTracker` would log a
            // second backup_done line.
            final Thread backupThread = BackupTask.thread;
            return backupThread != null && backupThread.isAlive();
        }

        @Override
        public File readBackupFolder() {
            return BackupTask.BACKUP_FOLDER;
        }
    }

    /**
     * {@code CoordinatorApi} reads the backup state of ServerUtilities 2.5 and later. {@code getDefaultBackupFolder()}
     * reads {@code backup_folder_path} of the ServerUtilities config on each call. In 2.4, the static initializer of
     * {@code BackupTask} read the same key once into {@code BACKUP_FOLDER}.
     */
    private record CoordinatorApi(Field backupActiveField, Method defaultFolderMethod) implements BackupApi {

        private static CoordinatorApi resolve() throws ReflectiveOperationException {
            final ClassLoader loader = BackupAccess.class.getClassLoader();
            final Class<?> coordinator = Class.forName("serverutils.task.backup.BackupCoordinator", false, loader);
            final Class<?> routine = Class.forName("serverutils.task.backup.BackupRoutine", false, loader);
            // BackupCoordinator.isRunning() is also true while /backup list or /backup prune reads the folder, and
            // `WorldSavingTracker` would log a backup_done line when a backup didn't run. backupActive is true only
            // from the start of a backup to the write of the backup's archive.
            final Field backupActive = coordinator.getDeclaredField("backupActive");
            backupActive.setAccessible(true);
            return new CoordinatorApi(backupActive, routine.getMethod("getDefaultBackupFolder"));
        }

        @Override
        public boolean isRunning() throws ReflectiveOperationException {
            return backupActiveField.getBoolean(null);
        }

        @Override
        public File readBackupFolder() throws ReflectiveOperationException {
            return (File) defaultFolderMethod.invoke(null);
        }
    }
}
