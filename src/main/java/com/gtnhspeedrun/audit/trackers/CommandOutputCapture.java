package com.gtnhspeedrun.audit.trackers;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import net.minecraft.command.ICommand;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.IChatComponent;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.gtnhspeedrun.audit.AuditConfig;
import com.gtnhspeedrun.audit.GtnhSpeedrunAudit;
import com.gtnhspeedrun.audit.core.JsonUtil;
import com.gtnhspeedrun.audit.core.SessionManager;

/**
 * Log the text that a query-style command sends back to the sender of the command, for the commands in the
 * {@code commandOutputCapture} allowlist.
 *
 * <p>
 * The command line alone doesn't contain what the command printed. For example, {@code /gt global_energy_display}
 * prints a team's wireless balance, and {@code /gamerule} with one argument prints the rule's value.
 *
 * <p>
 * Mixins call {@link #begin} and {@link #end} around {@code CommandHandler.executeCommand}, and {@link #captureLine}
 * for every chat line a hooked sender receives. A command runs synchronously on the calling thread, the server thread
 * or the RCON thread. Each thread therefore has a separate capture, in a thread-local. Only lines sent to the command's
 * sender are kept, because a notification to other ops is not the command's reply. A command off the allowlist is
 * buffered only inside the capture of an allowlisted command, so player conversation through {@code /msg} and similar
 * commands doesn't reach the log.
 */
public final class CommandOutputCapture {

    private static final int MAX_CHARS = 4096;
    private static final int MAX_LINES = 64;

    private static final ThreadLocal<Capture> CURRENT = new ThreadLocal<>();
    // static: built from the static AuditConfig list on first use, so a config change takes effect after a restart
    private static volatile Set<String> allowlist;

    private CommandOutputCapture() {}

    /**
     * Call at the head of {@code executeCommand}.
     *
     * @param resolved the command that the typed name maps to, or null for an unknown name
     */
    public static void begin(ICommandSender sender, String rawLine, ICommand resolved) {
        final Capture open = CURRENT.get();
        if (open != null && open.tick == readTick()) {
            // a command that runs another command. The inner command's lines go to the outer capture
            open.depth++;
            return;
        }
        // a capture from an earlier tick got no end() call, because an exception escaped executeCommand(). Drop the
        // capture
        CURRENT.remove();
        if (sender == null || GtnhSpeedrunAudit.session() == null
            || (sender.getEntityWorld() != null && sender.getEntityWorld().isRemote)) {
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
        if (!isAllowed(readAllowlist(), name, firstArg) && !isAllowed(readAllowlist(), typed, firstArg)) {
            return;
        }
        CURRENT.set(new Capture(sender, name, firstArg));
    }

    /** Keep the chat line when a capture is open and the receiver is the capture's sender. */
    public static void captureLine(ICommandSender receiver, IChatComponent message) {
        final Capture capture = CURRENT.get();
        if (capture == null || message == null || !isSameSender(capture.sender, receiver)) {
            return;
        }
        if (capture.lines.size() >= MAX_LINES || capture.chars >= MAX_CHARS) {
            capture.truncated = true;
            return;
        }
        String text = JsonUtil.stripFormatting(message.getUnformattedText());
        if (capture.chars + text.length() > MAX_CHARS) {
            text = text.substring(0, MAX_CHARS - capture.chars);
            capture.truncated = true;
        }
        capture.lines.add(text);
        capture.chars += text.length();
    }

    /** Call at the return of {@code executeCommand}. */
    public static void end() {
        final Capture capture = CURRENT.get();
        if (capture == null || --capture.depth > 0) {
            return;
        }
        CURRENT.remove();
        final SessionManager session = GtnhSpeedrunAudit.session();
        if (session == null) {
            return;
        }
        final JsonObject data = new JsonObject();
        data.addProperty("cmd", capture.command);
        if (capture.firstArg != null) {
            data.addProperty("firstArg", capture.firstArg);
        }
        data.addProperty("sender", capture.sender.getCommandSenderName());
        final JsonArray lines = new JsonArray();
        for (String line : capture.lines) {
            lines.add(JsonUtil.GSON.toJsonTree(line));
        }
        data.add("lines", lines);
        if (capture.truncated) {
            data.addProperty("truncated", true);
        }
        session.logger()
            .log("command_output", data);
    }

    private static int readTick() {
        final MinecraftServer server = MinecraftServer.getServer();
        return server == null ? -1 : server.getTickCounter();
    }

    public static boolean isAllowed(Set<String> allow, String name, String firstArg) {
        return allow.contains(name) || (firstArg != null && allow.contains(name + " " + firstArg));
    }

    private static Set<String> readAllowlist() {
        Set<String> built = allowlist;
        if (built == null) {
            built = new HashSet<>();
            for (String rawEntry : AuditConfig.commandOutputCapture) {
                final String entry = rawEntry.trim()
                    .toLowerCase(Locale.ROOT);
                if (!entry.isEmpty()) {
                    built.add(entry);
                }
            }
            allowlist = built;
        }
        return built;
    }

    /**
     * Match players by profile id. A respawn replaces the player entity, and the capture still has the entity from
     * before the respawn.
     */
    private static boolean isSameSender(ICommandSender sender, ICommandSender receiver) {
        if (sender == receiver) {
            return true;
        }
        return sender instanceof EntityPlayer senderPlayer && receiver instanceof EntityPlayer receiverPlayer
            && senderPlayer.getGameProfile()
                .getId()
                .equals(
                    receiverPlayer.getGameProfile()
                        .getId());
    }

    private static final class Capture {

        private final ICommandSender sender;
        private final String command;
        private final String firstArg;
        private final List<String> lines = new ArrayList<>();
        private int chars;
        private boolean truncated;
        private int depth = 1;
        private final int tick = readTick();

        private Capture(ICommandSender sender, String command, String firstArg) {
            this.sender = sender;
            this.command = command;
            this.firstArg = firstArg;
        }
    }
}
