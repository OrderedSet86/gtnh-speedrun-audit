package com.gtnhspeedrun.audit.trackers;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;

import org.jetbrains.annotations.Nullable;

import com.google.gson.JsonObject;
import com.gtnhspeedrun.audit.GtnhSpeedrunAudit;
import com.gtnhspeedrun.audit.core.SessionManager;
import com.gtnhspeedrun.audit.snapshot.PlayerPlace;

/**
 * Log a {@code teleport} line of type {@code COMMAND} for each player that a command moved, such as vanilla {@code /tp}
 * or CoFH's {@code /cofh tpx}. The place of every online player is read before and after the outermost command. A
 * player in another dimension afterwards, or more than {@link #MOVE_BLOCKS} away, was moved.
 *
 * <pre>
 *     "type":"COMMAND","command":"tp","by":"Alex","warmupSeconds":0,
 *     "from":{"dim":0,"pos":[133.5,105.0,-215.0],"yaw":-160.0,"pitch":30.0},"to":{"dim":-1,"pos":[56.5,95.0,9.5]}
 * </pre>
 *
 * A move that {@link TeleportSinks#logTeleport} already logged during the command has no second line. A teleport that
 * ends after the command, such as a ServerUtilities {@code /home} with a warmup, is not a command teleport.
 */
public final class CommandTeleports {

    public static final double MOVE_BLOCKS = 0.5;

    // static: the CommandHandler mixin has no instance. Commands run on the server thread, and RCon commands on the
    // RCon thread, so each thread has a separate frame
    private static final ThreadLocal<Frame> FRAMES = new ThreadLocal<>();

    private CommandTeleports() {}

    /** Call at the head of each command. A nested command adds a level to the frame of the outermost command. */
    public static void begin(ICommandSender sender, String commandName) {
        final MinecraftServer server = MinecraftServer.getServer();
        final Frame open = FRAMES.get();
        if (open != null && server != null && open.tick == server.getTickCounter()) {
            open.depth++;
            return;
        }
        // a frame from an earlier tick got no end() call, because an exception escaped executeCommand(). Drop the frame
        FRAMES.remove();
        if (!(server != null && GtnhSpeedrunAudit.session() != null)) {
            return;
        }
        final Frame frame = new Frame(commandName, sender.getCommandSenderName(), server.getTickCounter());
        for (Object entry : server.getConfigurationManager().playerEntityList) {
            final EntityPlayerMP player = (EntityPlayerMP) entry;
            frame.before.put(player, PlayerPlace.describePlace(player));
        }
        FRAMES.set(frame);
    }

    /** Call at the return of each command. The outermost command's return logs the moves. */
    public static void end() {
        final Frame frame = FRAMES.get();
        if (frame == null) {
            return;
        }
        if (frame.depth > 0) {
            frame.depth--;
            return;
        }
        FRAMES.remove();
        final SessionManager session = GtnhSpeedrunAudit.session();
        if (session == null) {
            return;
        }
        for (Map.Entry<EntityPlayerMP, JsonObject> entry : frame.before.entrySet()) {
            final EntityPlayerMP player = entry.getKey();
            final JsonObject from = entry.getValue();
            if (!(isMoved(player, from) && !frame.loggedPlayers.contains(player))) {
                continue;
            }
            final JsonObject data = new JsonObject();
            data.addProperty("command", frame.commandName);
            data.addProperty("by", frame.senderName);
            TeleportSinks.logMovedTeleport(player, from, data);
        }
    }

    /** Mark {@code player}'s teleport as logged, so the outermost command's return does not log the move again. */
    public static void noteLogged(EntityPlayerMP player) {
        final Frame frame = FRAMES.get();
        if (frame != null) {
            frame.loggedPlayers.add(player);
        }
    }

    private static boolean isMoved(EntityPlayerMP player, JsonObject from) {
        if (player.dimension != from.get("dim")
            .getAsInt()) {
            return true;
        }
        final double dx = player.posX - from.getAsJsonArray("pos")
            .get(0)
            .getAsDouble();
        final double dy = player.posY - from.getAsJsonArray("pos")
            .get(1)
            .getAsDouble();
        final double dz = player.posZ - from.getAsJsonArray("pos")
            .get(2)
            .getAsDouble();
        return dx * dx + dy * dy + dz * dz > MOVE_BLOCKS * MOVE_BLOCKS;
    }

    /** The players' places before the outermost command, and the moves logged during the command. */
    private static final class Frame {

        private final String commandName;
        private final String senderName;
        private final int tick;
        private final Map<EntityPlayerMP, JsonObject> before = new IdentityHashMap<>();
        private final Set<EntityPlayerMP> loggedPlayers = Collections.newSetFromMap(new IdentityHashMap<>());
        private int depth;

        private Frame(String commandName, @Nullable String senderName, int tick) {
            this.commandName = commandName;
            this.senderName = senderName;
            this.tick = tick;
        }
    }
}
