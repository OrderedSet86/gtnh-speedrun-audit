package com.gtnhspeedrun.audit.trackers;

import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayerMP;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.gtnhspeedrun.audit.GtnhSpeedrunAudit;
import com.gtnhspeedrun.audit.core.SessionManager;
import com.gtnhspeedrun.audit.snapshot.PlayerPlace;

/**
 * Log the ServerUtilities teleports that the ServerUtilities mixins report, with the player's place and the
 * destination.
 *
 * <p>
 * The signatures have only vanilla, JDK and audit types, so this class loads on a pack without ServerUtilities. The
 * mixins call the sink on the server thread.
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
        final JsonObject data = new JsonObject();
        data.addProperty(
            "uuid",
            player.getGameProfile()
                .getId()
                .toString());
        data.addProperty("name", player.getCommandSenderName());
        data.addProperty("type", move.type());
        data.addProperty("warmupSeconds", move.warmupSeconds());
        data.add("from", PlayerPlace.describePlace(player));
        final JsonObject destination = new JsonObject();
        destination.addProperty("dim", move.dim());
        final JsonArray position = new JsonArray();
        position.add(new JsonPrimitive(PlayerPlace.roundToTenth(move.x())));
        position.add(new JsonPrimitive(PlayerPlace.roundToTenth(move.y())));
        position.add(new JsonPrimitive(PlayerPlace.roundToTenth(move.z())));
        destination.add("pos", position);
        data.add("to", destination);
        session.logger()
            .log("teleport", data);
    }

    /**
     * One teleport: the ServerUtilities teleport type or {@code TPL}, the warmup the player waited, and the
     * destination feet position.
     */
    public record Move(String type, int warmupSeconds, int dim, double x, double y, double z) {}
}
