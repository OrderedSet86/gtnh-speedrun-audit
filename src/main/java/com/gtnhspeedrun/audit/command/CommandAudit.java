package com.gtnhspeedrun.audit.command;

import java.io.IOException;
import java.util.List;

import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.StatCollector;

import com.google.gson.JsonObject;
import com.gtnhspeedrun.audit.GtnhSpeedrunAudit;
import com.gtnhspeedrun.audit.core.LogWriter;
import com.gtnhspeedrun.audit.core.SessionManager;
import com.gtnhspeedrun.audit.core.TickClock;
import com.gtnhspeedrun.audit.core.Verifier;

/** Run the {@code /audit} subcommands: status, verify, snapshot and export. */
public final class CommandAudit extends CommandBase {

    @Override
    public String getCommandName() {
        return "audit";
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return StatCollector.translateToLocal("gtnhspeedrunaudit.command.usage");
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 2;
    }

    @Override
    public boolean canCommandSenderUseCommand(ICommandSender sender) {
        // in singleplayer the owner is op only with cheats on. Let the owner run /audit with cheats off
        final MinecraftServer server = MinecraftServer.getServer();
        if (server != null && server.isSinglePlayer()
            && sender instanceof EntityPlayerMP player
            && server.getServerOwner()
                .equalsIgnoreCase(player.getCommandSenderName())) {
            return true;
        }
        return super.canCommandSenderUseCommand(sender);
    }

    @Override
    public List<String> addTabCompletionOptions(ICommandSender sender, String[] args) {
        return args.length == 1 ? getListOfStringsMatchingLastWord(args, "status", "verify", "snapshot", "export")
            : null;
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        final SessionManager session = GtnhSpeedrunAudit.session();
        if (session == null) {
            reply(sender, "gtnhspeedrunaudit.command.not_running");
            return;
        }
        if (args.length == 0) {
            throw new WrongUsageException(getCommandUsage(sender));
        }
        switch (args[0]) {
            case "status" -> reportStatus(sender, session);
            case "verify" -> verify(sender, session);
            case "snapshot" -> snapshotNow(sender, session);
            case "export" -> ExportCommand.export(sender, session);
            default -> throw new WrongUsageException(getCommandUsage(sender));
        }
    }

    // The server resolves every lang key of this command. A client without the mod has no lang file and would show
    // the raw key. The dedicated server of Forge 1614 loads the en_US.lang of each mod too.
    public static void reply(ICommandSender sender, String key, Object... values) {
        sender.addChatMessage(
            new ChatComponentText(
                StatCollector.translateToLocalFormatted(
                    "gtnhspeedrunaudit.command.reply",
                    StatCollector.translateToLocalFormatted(key, values))));
    }

    /** Reply with the session, the chain head, the three clocks, the writer state and the audit directory. */
    private static void reportStatus(ICommandSender sender, SessionManager session) {
        final LogWriter writer = session.logger()
            .writer();
        final TickClock clock = session.logger()
            .clock();
        reply(sender, "gtnhspeedrunaudit.command.status.session", session.sessionIndex(), session.verifyVerdict());
        reply(
            sender,
            "gtnhspeedrunaudit.command.status.clocks",
            writer.publishedSeq(),
            formatTicks(clock.get()),
            formatTicks(clock.getOnline()),
            formatRta(session.anchor().timingStartWallMs));
        if (writer.isDead()) {
            reply(sender, "gtnhspeedrunaudit.command.status.writer_dead");
        } else {
            reply(sender, "gtnhspeedrunaudit.command.status.writer_alive", writer.queueDepth());
        }
        reply(
            sender,
            "gtnhspeedrunaudit.command.status.dir",
            session.auditDir()
                .toAbsolutePath());
    }

    private static String formatTicks(long ticks) {
        final long TICKS_PER_SECOND = 20;
        final long seconds = ticks / TICKS_PER_SECOND;
        return StatCollector
            .translateToLocalFormatted("gtnhspeedrunaudit.command.ticks", ticks, seconds / 3600, seconds % 3600 / 60);
    }

    private static String formatRta(long timingStartWallMs) {
        final long MS_PER_SECOND = 1000;
        if (timingStartWallMs == 0) {
            return StatCollector.translateToLocal("gtnhspeedrunaudit.command.rta.not_started");
        }
        final long seconds = (System.currentTimeMillis() - timingStartWallMs) / MS_PER_SECOND;
        return StatCollector
            .translateToLocalFormatted("gtnhspeedrunaudit.command.rta", seconds / 3600, seconds % 3600 / 60);
    }

    /**
     * Verify the chain up to the current head on a background thread, because the walk hashes months of log again.
     * The result goes to {@code sender} and into a verify_report line.
     */
    private static void verify(ICommandSender sender, SessionManager session) {
        final long upToSeq = session.logger()
            .writer()
            .publishedSeq();
        final String uuid = session.anchor().worldAuditUuid;
        reply(sender, "gtnhspeedrunaudit.command.verify.started", upToSeq);
        final Thread verifyThread = new Thread(() -> {
            final JsonObject data = new JsonObject();
            try {
                final Verifier.Result result = Verifier.verify(session.logDir(), uuid, upToSeq);
                data.addProperty("verdict", result.verdict());
                data.addProperty("lines", result.lines());
                data.addProperty("lastSeq", result.lastSeq());
                for (String problem : result.problems()) {
                    reply(sender, "gtnhspeedrunaudit.command.verify.problem", problem);
                }
                reply(
                    sender,
                    "gtnhspeedrunaudit.command.verify.result",
                    result.verdict(),
                    result.lines(),
                    result.firstSeq(),
                    result.lastSeq());
            } catch (IOException | RuntimeException e) {
                data.addProperty("verdict", "VERIFY_ERROR");
                data.addProperty("error", String.valueOf(e));
                reply(sender, "gtnhspeedrunaudit.command.verify.failed", e);
            }
            session.logger()
                .log("verify_report", data);
        }, "SpeedrunAudit-Verify");
        verifyThread.setDaemon(true);
        verifyThread.start();
    }

    private static void snapshotNow(ICommandSender sender, SessionManager session) {
        final int hookCount = session.runSnapshotHooks();
        if (hookCount == 0) {
            reply(sender, "gtnhspeedrunaudit.command.snapshot.none");
            return;
        }
        reply(sender, "gtnhspeedrunaudit.command.snapshot.queued", hookCount);
    }
}
