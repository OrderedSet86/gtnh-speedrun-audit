package com.gtnhspeedrun.audit.trackers;

import java.util.Map;

import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayerMP;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.gtnhspeedrun.audit.GtnhSpeedrunAudit;
import com.gtnhspeedrun.audit.core.SessionManager;
import com.gtnhspeedrun.audit.snapshot.PlayerPlace;

/**
 * Log the teleports of a player: the ServerUtilities teleports that the ServerUtilities mixins report, and the moves
 * after a command, from {@link CommandTeleports}. Each line has the player's place and the destination.
 *
 * <p>
 * The signatures have only vanilla, JDK and audit types, so this class loads on a pack without ServerUtilities.
 */
public final class TeleportSinks {

    private TeleportSinks() {}

    /**
     * Log a {@code teleport} line for a player just before {@code move} moves the player. Nothing is logged for an
     * entity that is not a player.
     *
     * <pre>
     *     "type":"HOME","warmupSeconds":0,"from":{"dim":0,"pos":[133.5,105.0,-215.0],"yaw":-160.0,"pitch":30.0},
     *     "to":{"dim":0,"pos":[133.5,105.0,-211.5]}
     * </pre>
     */
    public static void logTeleport(Entity entity, Move move) {
        final SessionManager session = GtnhSpeedrunAudit.session();
        if (!(session != null && entity instanceof EntityPlayerMP player)) {
            return;
        }
        CommandTeleports.noteLogged(player);
        final JsonObject data = new JsonObject();
        data.addProperty("type", move.type());
        data.addProperty("warmupSeconds", move.warmupSeconds());
        data.add("from", PlayerPlace.describePlace(player));
        data.add("to", describeDestination(move.dim(), new double[] { move.x(), move.y(), move.z() }));
        logTeleportLine(session, player, data);
    }

    /**
     * Log a {@code teleport} line of type {@code COMMAND} for a player that a command has moved already, from the place
     * {@code from} read before the command to the player's place now. The members of {@code extra} come after the type.
     */
    public static void logMovedTeleport(EntityPlayerMP player, JsonObject from, JsonObject extra) {
        final SessionManager session = GtnhSpeedrunAudit.session();
        if (session == null) {
            return;
        }
        final JsonObject data = new JsonObject();
        data.addProperty("type", "COMMAND");
        for (Map.Entry<String, JsonElement> member : extra.entrySet()) {
            data.add(member.getKey(), member.getValue());
        }
        data.addProperty("warmupSeconds", 0);
        data.add("from", from);
        data.add("to", describeDestination(player.dimension, new double[] { player.posX, player.posY, player.posZ }));
        logTeleportLine(session, player, data);
    }

    private static JsonObject describeDestination(int dim, double[] feet) {
        final JsonObject destination = new JsonObject();
        destination.addProperty("dim", dim);
        final JsonArray position = new JsonArray();
        for (double coordinate : feet) {
            position.add(new JsonPrimitive(PlayerPlace.roundToTenth(coordinate)));
        }
        destination.add("pos", position);
        return destination;
    }

    /** Write the line with the player's uuid and name first, so the field order is the same for every type. */
    private static void logTeleportLine(SessionManager session, EntityPlayerMP player, JsonObject fields) {
        final JsonObject data = new JsonObject();
        data.addProperty(
            "uuid",
            player.getGameProfile()
                .getId()
                .toString());
        data.addProperty("name", player.getCommandSenderName());
        for (Map.Entry<String, JsonElement> member : fields.entrySet()) {
            data.add(member.getKey(), member.getValue());
        }
        session.logger()
            .log("teleport", data);
    }

    /**
     * One teleport: the ServerUtilities teleport type or {@code TPL}, the warmup the player waited, and the
     * destination feet position.
     */
    public record Move(String type, int warmupSeconds, int dim, double x, double y, double z) {}
}
