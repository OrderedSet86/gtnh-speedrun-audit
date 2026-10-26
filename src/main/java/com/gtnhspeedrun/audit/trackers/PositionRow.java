package com.gtnhspeedrun.audit.trackers;

import com.google.gson.JsonArray;
import com.google.gson.JsonPrimitive;
import com.gtnhspeedrun.audit.core.PlayerNumbers;

/**
 * One player's row in a {@code pos_sample} line.
 *
 * <pre>
 *     [player, mode, dim, x, y, z, yaw, pitch]
 *     [0,"s",0,1335,1050,-2150,-160,30]
 * </pre>
 *
 * {@code player} is the player's {@link PlayerNumbers} number. The position is the feet, in tenths of a block.
 * {@code yaw} is in whole degrees from -180 to 179, and {@code pitch} in whole degrees from -90 (up) to 90 (down).
 *
 * @param mode {@code s} survival, {@code c} creative, {@code a} adventure, an empty string for a player with no
 *             gamemode, such as a FakePlayer, or the full name of another gamemode, such as {@code spectator}
 */
public record PositionRow(int player, String mode, int dim, int xTenths, int yTenths, int zTenths, int yaw, int pitch) {

    public static final int STILL_TENTHS = 10;
    public static final int STILL_DEGREES = 10;

    /**
     * Return whether this row is in the dimension and mode of {@code last}, within {@link #STILL_TENTHS} on each axis,
     * and within {@link #STILL_DEGREES} of the yaw and of the pitch of {@code last}.
     */
    public boolean isStillFrom(PositionRow last) {
        return dim == last.dim && mode.equals(last.mode)
            && Math.abs(xTenths - last.xTenths) <= STILL_TENTHS
            && Math.abs(yTenths - last.yTenths) <= STILL_TENTHS
            && Math.abs(zTenths - last.zTenths) <= STILL_TENTHS
            && computeYawGap(yaw, last.yaw) <= STILL_DEGREES
            && Math.abs(pitch - last.pitch) <= STILL_DEGREES;
    }

    public static PositionRow parseRow(JsonArray row) {
        return new PositionRow(
            row.get(0)
                .getAsInt(),
            row.get(1)
                .getAsString(),
            row.get(2)
                .getAsInt(),
            row.get(3)
                .getAsInt(),
            row.get(4)
                .getAsInt(),
            row.get(5)
                .getAsInt(),
            row.get(6)
                .getAsInt(),
            row.get(7)
                .getAsInt());
    }

    public JsonArray toJson() {
        final JsonArray row = new JsonArray();
        row.add(new JsonPrimitive(player));
        row.add(new JsonPrimitive(mode));
        row.add(new JsonPrimitive(dim));
        row.add(new JsonPrimitive(xTenths));
        row.add(new JsonPrimitive(yTenths));
        row.add(new JsonPrimitive(zTenths));
        row.add(new JsonPrimitive(yaw));
        row.add(new JsonPrimitive(pitch));
        return row;
    }

    /** Return the angle between two yaws, in degrees from 0 to 180. A yaw of 179 is 2 degrees from -179. */
    public static int computeYawGap(int yaw, int otherYaw) {
        final int FULL_TURN = 360;
        final int gap = Math.floorMod(yaw - otherYaw, FULL_TURN);
        return Math.min(gap, FULL_TURN - gap);
    }
}
