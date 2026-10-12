package com.gtnhspeedrun.audit.compat;

/**
 * ServerUtilities' backup state. The serverutils classes only exist when ServerUtilities is installed, so the
 * touch of BackupTask lives in its own class behind the Compat gate, like BaublesAccess.
 */
public final class BackupAccess {

    private BackupAccess() {}

    /** Whether a ServerUtilities backup is running, or null without ServerUtilities. */
    public static Boolean running() {
        if (!Compat.SERVER_UTILITIES.isLoaded()) {
            return null;
        }
        try {
            return Holder.running();
        } catch (NoClassDefFoundError | NoSuchMethodError | RuntimeException e) {
            return null;
        }
    }

    /**
     * The newest file in ServerUtilities' backup folder — the one a backup that just finished wrote: name, size
     * and modification time. Adds {@code folderUnavailable} instead when the folder can't be read.
     */
    public static void describeNewest(com.google.gson.JsonObject into) {
        try {
            final java.io.File[] files = Holder.folder()
                .listFiles();
            java.io.File newest = null;
            if (files != null) {
                for (java.io.File f : files) {
                    if (f.isFile() && (newest == null || f.lastModified() > newest.lastModified())) {
                        newest = f;
                    }
                }
            }
            if (newest == null) {
                into.addProperty("folderEmpty", true);
                return;
            }
            into.addProperty("file", newest.getName());
            into.addProperty("bytes", newest.length());
            into.addProperty("lastModified", newest.lastModified());
        } catch (NoClassDefFoundError | NoSuchFieldError | RuntimeException e) {
            into.addProperty("folderUnavailable", String.valueOf(e));
        }
    }

    private static final class Holder {

        static boolean running() {
            return serverutils.task.backup.BackupTask.isBackupRunning();
        }

        static java.io.File folder() {
            return serverutils.task.backup.BackupTask.BACKUP_FOLDER;
        }
    }
}
