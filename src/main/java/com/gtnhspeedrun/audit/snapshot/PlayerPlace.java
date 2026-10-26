package com.gtnhspeedrun.audit.snapshot;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.MathHelper;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

/**
 * Write a player's dimension, position and facing into a log line or a snapshot.
 *
 * <pre>
 *     "dim":0,"pos":[133.5,105.0,-215.0],"yaw":-160.0,"pitch":30.0
 * </pre>
 *
 * The position is the player's feet, rounded to 0.1 block. {@code yaw} is in degrees from -180 to 180. A yaw of 0 is
 * south (+z), and 90 is west (-x). {@code pitch} is in degrees from -90 (up) to 90 (down). Both angles are rounded to
 * 0.1 degree.
 */
public final class PlayerPlace {

    private PlayerPlace() {}

    /** Return a new object with only the place fields of {@link #addPlace}. */
    public static JsonObject describePlace(EntityPlayerMP player) {
        final JsonObject place = new JsonObject();
        addPlace(place, player);
        return place;
    }

    public static void addPlace(JsonObject data, EntityPlayerMP player) {
        data.addProperty("dim", player.dimension);
        final JsonArray position = new JsonArray();
        position.add(new JsonPrimitive(roundToTenth(player.posX)));
        position.add(new JsonPrimitive(roundToTenth(player.posY)));
        position.add(new JsonPrimitive(roundToTenth(player.posZ)));
        data.add("pos", position);
        // Minecraft does not wrap rotationYaw. The value passes 360 as the player turns
        data.addProperty("yaw", roundToTenth(MathHelper.wrapAngleTo180_float(player.rotationYaw)));
        data.addProperty("pitch", roundToTenth(player.rotationPitch));
    }

    public static double roundToTenth(double value) {
        final double TENTHS = 10.0;
        return Math.round(value * TENTHS) / TENTHS;
    }
}
