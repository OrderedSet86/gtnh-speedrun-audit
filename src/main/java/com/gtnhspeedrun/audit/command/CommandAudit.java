package com.gtnhspeedrun.audit.command;

import java.util.Arrays;
import java.util.List;

import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;
import net.minecraft.util.ChatComponentText;

import com.google.gson.JsonObject;
import com.gtnhspeedrun.audit.GtnhSpeedrunAudit;
import com.gtnhspeedrun.audit.core.SessionManager;
import com.gtnhspeedrun.audit.core.Verifier;

public class CommandAudit extends CommandBase {

    @Override
    public String getCommandName() {
        return "audit";
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "/audit status|verify|snapshot|export";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 2;
    }

    @Override
    public List<String> addTabCompletionOptions(ICommandSender sender, String[] args) {
        return args.length == 1
            ? getListOfStringsMatchingLastWord(args, "status", "verify", "snapshot", "export")
            : null;
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        final SessionManager session = GtnhSpeedrunAudit.session();
        if (session == null) {
            reply(sender, "Audit is NOT running — this run is not being audited (see server log).");
            return;
        }
        if (args.length == 0) {
            throw new WrongUsageException(getCommandUsage(sender));
        }
        switch (args[0]) {
            case "status" -> status(sender, session);
            case "verify" -> verify(sender, session);
            case "snapshot" -> session.snapshotNow(sender);
            case "export" -> session.export(sender);
            default -> throw new WrongUsageException(getCommandUsage(sender));
        }
    }

    private void status(ICommandSender sender, SessionManager session) {
        reply(sender, "session #" + session.sessionIndex() + "  startup verdict: " + session.verifyVerdict());
        reply(sender, "chain head seq " + session.logger().writer().publishedSeq() + ", run clock "
            + formatTicks(session.logger().clock().get()));
        reply(sender, session.logger().writer().isDead()
            ? "WRITER DEAD — logging has stopped, check the server log!"
            : "writer alive, queue depth " + session.logger().writer().queueDepth());
        reply(sender, "audit dir: " + session.auditDir().getAbsolutePath());
    }

    /** The walk re-hashes months of log; keep the server thread out of it. */
    private void verify(ICommandSender sender, SessionManager session) {
        final long upToSeq = session.logger().writer().publishedSeq();
        final String uuid = session.anchor().worldAuditUuid;
        reply(sender, "verifying chain up to seq " + upToSeq + "…");
        final Thread t = new Thread(() -> {
            JsonObject data = new JsonObject();
            try {
                final Verifier.Result result = Verifier.verify(session.logDir(), uuid, upToSeq);
                data.addProperty("verdict", result.verdict);
                data.addProperty("lines", result.lines);
                data.addProperty("lastSeq", result.lastSeq);
                for (String p : result.problems) {
                    reply(sender, "  " + p);
                }
                reply(sender, "chain " + result.verdict + " (" + result.lines + " lines, seq " + result.firstSeq
                    + ".." + result.lastSeq + ")");
            } catch (Exception e) {
                data.addProperty("verdict", "VERIFY_ERROR");
                data.addProperty("error", String.valueOf(e));
                reply(sender, "verify failed: " + e);
            }
            session.logger().log("verify_report", data);
        }, "SpeedrunAudit-Verify");
        t.setDaemon(true);
        t.start();
    }

    private static String formatTicks(long ticks) {
        return ticks + " ticks (" + (ticks / 20 / 3600) + "h" + (ticks / 20 % 3600 / 60) + "m in-game)";
    }

    public static void reply(ICommandSender sender, String msg) {
        sender.addChatMessage(new ChatComponentText("[audit] " + msg));
    }
}
