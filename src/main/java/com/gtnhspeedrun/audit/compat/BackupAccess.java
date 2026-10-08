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

    private static final class Holder {

        static boolean running() {
            return serverutils.task.backup.BackupTask.isBackupRunning();
        }
    }
}
