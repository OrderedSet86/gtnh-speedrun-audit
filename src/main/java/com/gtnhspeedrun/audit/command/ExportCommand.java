package com.gtnhspeedrun.audit.command;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import net.minecraft.command.ICommandSender;
import net.minecraft.util.StatCollector;

import com.gtnhspeedrun.audit.core.AuditDirs;
import com.gtnhspeedrun.audit.core.LogWriter;
import com.gtnhspeedrun.audit.core.SessionManager;
import com.gtnhspeedrun.audit.core.TickClock;
import com.gtnhspeedrun.audit.export.AuditExport;

/** Run {@code /audit export}: build the submission zip of a session and tell the sender where the zip is. */
public final class ExportCommand {

    private ExportCommand() {}

    /**
     * Zip every log and snapshot, and verify the whole chain again, on a background thread. The background thread
     * sends {@code sender} the path of the zip, or the failure.
     */
    public static void export(ICommandSender sender, SessionManager session) {
        final long DRAIN_TIMEOUT_MS = 5_000;
        final LogWriter writer = session.logger()
            .writer();
        final TickClock clock = session.logger()
            .clock();
        final AuditDirs dirs = session.dirs();
        writer.drain(DRAIN_TIMEOUT_MS);
        final AuditExport bundle = new AuditExport(
            dirs,
            new AuditExport.AnchorView(
                session.anchor().worldAuditUuid,
                writer.publishedSeq(),
                clock.get(),
                clock.getOnline(),
                session.anchor().timingStartWallMs));
        CommandAudit
            .reply(sender, "gtnhspeedrunaudit.command.export.building", formatMegabytes(directorySize(dirs.log())));
        // a host's web panel lists files by their path under the server folder, not by the absolute path in the
        // container
        final boolean dedicated = session.server()
            .isDedicatedServer();
        final String rootName = StatCollector.translateToLocal(
            dedicated ? "gtnhspeedrunaudit.command.export.folder.server"
                : "gtnhspeedrunaudit.command.export.folder.instance");
        final String landmarkKey = dedicated ? "gtnhspeedrunaudit.command.export.landmark.server"
            : "gtnhspeedrunaudit.command.export.landmark.instance";
        // SessionManager.stopped() sets the anchor to null, and the server can stop while the zip is written
        final String worldAuditUuid = session.anchor().worldAuditUuid;
        final Thread exportThread = new Thread(() -> {
            try {
                final Path zip = bundle.writeZip();
                CommandAudit.reply(sender, "gtnhspeedrunaudit.command.export.ready", formatMegabytes(Files.size(zip)));
                CommandAudit.reply(
                    sender,
                    "gtnhspeedrunaudit.command.export.path",
                    rootName,
                    worldAuditUuid,
                    zip.getFileName());
                CommandAudit.reply(sender, landmarkKey, rootName);
            } catch (IOException | RuntimeException e) {
                CommandAudit.reply(sender, "gtnhspeedrunaudit.command.export.failed", e);
            }
        }, "SpeedrunAudit-Export");
        exportThread.setDaemon(true);
        exportThread.start();
    }

    private static long directorySize(Path dir) {
        long bytes = 0;
        for (Path entry : AuditDirs.listEntriesOrEmpty(dir)) {
            try {
                bytes += Files.size(entry);
            } catch (IOException e) {
                // the size is an estimate for a chat line, so an unreadable entry counts as 0 bytes
            }
        }
        return bytes;
    }

    private static String formatMegabytes(long bytes) {
        final long HALF_MEGABYTE = 500_000;
        final double MEGABYTE = 1_000_000.0;
        return bytes < HALF_MEGABYTE ? "<1" : String.valueOf(Math.round(bytes / MEGABYTE));
    }
}
