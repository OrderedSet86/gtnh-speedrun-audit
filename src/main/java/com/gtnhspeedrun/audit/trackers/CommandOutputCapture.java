package com.gtnhspeedrun.audit.trackers;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import net.minecraft.command.ICommand;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.IChatComponent;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.gtnhspeedrun.audit.AuditConfig;
import com.gtnhspeedrun.audit.GtnhSpeedrunAudit;
import com.gtnhspeedrun.audit.core.JsonUtil;
import com.gtnhspeedrun.audit.core.SessionManager;

/**
 * The text a command sent back to whoever ran it, for an allowlist of query-style commands
 * (`commandOutputCapture`): `/gt global_energy_display` prints a team's wireless balance, `/gamerule` with one
 * argument prints the value. The command line alone never says what they printed (R5 was settled by a chat
 * screenshot).
 *
 * <p>
 * The vanilla mixins bracket CommandHandler.executeCommand and feed every chat line the sender receives in
 * between. Commands execute synchronously on the calling thread (server or RCON), so a thread-local scopes the
 * capture to exactly one command, and only lines addressed to its own sender are kept — notifications to other
 * ops are not this command's reply. Anything off the allowlist is never buffered, so player conversation through
 * /msg and friends cannot end up in the log.
 */
public final class CommandOutputCapture {

    private static final int MAX_CHARS = 4096;
    private static final int MAX_LINES = 64;

    private static final class Capture {

        final ICommandSender sender;
        final String command;
        final String firstArg;
        final List<String> lines = new ArrayList<>();
        int chars;
        boolean truncated;
        int depth = 1;
        final int tick = tick();

        Capture(ICommandSender sender, String command, String firstArg) {
            this.sender = sender;
            this.command = command;
            this.firstArg = firstArg;
        }
    }

    private static final ThreadLocal<Capture> CURRENT = new ThreadLocal<>();
    private static volatile Set<String> allowlist;

    private CommandOutputCapture() {}

    /** HEAD of executeCommand. {@code resolved} is the command the name maps to, or null if unknown. */
    public static void begin(ICommandSender sender, String rawLine, ICommand resolved) {
        final Capture open = CURRENT.get();
        if (open != null && open.tick == tick()) {
            open.depth++; // a command running another command: the outer capture keeps collecting
            return;
        }
        // A capture from an earlier tick never saw its RETURN (an Error escaped executeCommand); drop it.
        CURRENT.remove();
        if (sender == null || GtnhSpeedrunAudit.session() == null
            || sender.getEntityWorld() != null && sender.getEntityWorld().isRemote) {
            return;
        }
        String line = rawLine == null ? "" : rawLine.trim();
        if (line.startsWith("/")) {
            line = line.substring(1);
        }
        final String[] parts = line.split(" ");
        final String typed = parts[0].toLowerCase(Locale.ROOT);
        final String name = resolved == null ? typed
            : resolved.getCommandName()
                .toLowerCase(Locale.ROOT);
        final String firstArg = parts.length > 1 ? parts[1].toLowerCase(Locale.ROOT) : null;
        if (!allowed(allowlist(), name, firstArg) && !allowed(allowlist(), typed, firstArg)) {
            return;
        }
        CURRENT.set(new Capture(sender, name, firstArg));
    }

    /** Every chat line any of the hooked senders receives; kept only inside a capture and only for its sender. */
    public static void line(ICommandSender receiver, IChatComponent message) {
        final Capture c = CURRENT.get();
        if (c == null || message == null || !sameSender(c.sender, receiver)) {
            return;
        }
        if (c.lines.size() >= MAX_LINES || c.chars >= MAX_CHARS) {
            c.truncated = true;
            return;
        }
        String text = JsonUtil.stripFormatting(message.getUnformattedText());
        if (c.chars + text.length() > MAX_CHARS) {
            text = text.substring(0, MAX_CHARS - c.chars);
            c.truncated = true;
        }
        c.lines.add(text);
        c.chars += text.length();
    }

    /** RETURN of executeCommand (and the exceptional path through it). */
    public static void end() {
        final Capture c = CURRENT.get();
        if (c == null || --c.depth > 0) {
            return;
        }
        CURRENT.remove();
        final SessionManager session = GtnhSpeedrunAudit.session();
        if (session == null) {
            return;
        }
        final JsonObject data = new JsonObject();
        data.addProperty("cmd", c.command);
        if (c.firstArg != null) {
            data.addProperty("firstArg", c.firstArg);
        }
        data.addProperty("sender", c.sender.getCommandSenderName());
        final JsonArray lines = new JsonArray();
        for (String l : c.lines) {
            lines.add(JsonUtil.GSON.toJsonTree(l));
        }
        data.add("lines", lines);
        if (c.truncated) {
            data.addProperty("truncated", true);
        }
        session.logger()
            .log("command_output", data);
    }

    private static int tick() {
        final net.minecraft.server.MinecraftServer server = net.minecraft.server.MinecraftServer.getServer();
        return server == null ? -1 : server.getTickCounter();
    }

    static boolean allowed(Set<String> allow, String name, String firstArg) {
        return allow.contains(name) || firstArg != null && allow.contains(name + " " + firstArg);
    }

    private static Set<String> allowlist() {
        Set<String> a = allowlist;
        if (a == null) {
            a = new HashSet<>();
            for (String e : AuditConfig.commandOutputCapture) {
                final String t = e.trim()
                    .toLowerCase(Locale.ROOT);
                if (!t.isEmpty()) {
                    a.add(t);
                }
            }
            allowlist = a;
        }
        return a;
    }

    /** Players are matched by profile id: a respawn replaces the entity while the command still holds the old one. */
    private static boolean sameSender(ICommandSender a, ICommandSender b) {
        if (a == b) {
            return true;
        }
        return a instanceof EntityPlayer pa && b instanceof EntityPlayer pb
            && pa.getGameProfile()
                .getId()
                .equals(
                    pb.getGameProfile()
                        .getId());
    }
}
