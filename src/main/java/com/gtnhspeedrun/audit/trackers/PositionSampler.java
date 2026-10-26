package com.gtnhspeedrun.audit.trackers;

import java.util.HashMap;
import java.util.Map;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.MathHelper;
import net.minecraft.world.WorldSettings;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.gtnhspeedrun.audit.core.AuditLogger;
import com.gtnhspeedrun.audit.core.PlayerNumbers;
import com.gtnhspeedrun.audit.core.SessionManager;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/**
 * Log the place and facing of each online player at a fixed interval, with a shorter interval outside survival.
 *
 * <p>
 * Each {@code pos_sample} line has a {@link PositionRow} for every player due on that tick. A due player gets no row
 * when {@link PositionRow#isStillFrom} is true for the new row and the player's last row. A player's first sample after
 * a join always has a row. A player is due on each multiple of the interval of the player's gamemode, so a player who
 * changes into creative is sampled within the shorter interval. The intervals are in server ticks, so sampling stops
 * while an integrated server is paused.
 */
public final class PositionSampler {

    private final AuditLogger logger;
    private final MinecraftServer server;
    private final Intervals intervals;
    private final PlayerNumbers playerNumbers;
    private final Map<Integer, PositionRow> lastRows = new HashMap<>();
    private long tickCounter;

    public PositionSampler(SessionManager session, MinecraftServer server, Intervals intervals) {
        this.logger = session.logger();
        this.server = server;
        this.intervals = intervals;
        this.playerNumbers = session.playerNumbers();
    }

    /** Log one {@code pos_sample} line on a tick with a row, and no line on any other tick. */
    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        tickCounter++;
        // null until the first row: on most ticks no player is due, and the loop allocates nothing
        JsonArray rows = null;
        for (Object rawPlayer : server.getConfigurationManager().playerEntityList) {
            final EntityPlayerMP player = (EntityPlayerMP) rawPlayer;
            final WorldSettings.GameType gameType = player.theItemInWorldManager.getGameType();
            if (!intervals.isDue(tickCounter, gameType)) {
                continue;
            }
            final PositionRow row = describeRow(player, gameType);
            final PositionRow lastRow = lastRows.get(row.player());
            if (lastRow != null && row.isStillFrom(lastRow)) {
                continue;
            }
            lastRows.put(row.player(), row);
            if (rows == null) {
                rows = new JsonArray();
            }
            rows.add(row.toJson());
        }
        if (rows != null) {
            final JsonObject data = new JsonObject();
            data.add("players", rows);
            logger.log("pos_sample", data);
        }
    }

    @SubscribeEvent
    public void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        lastRows.remove(
            playerNumbers.numberFor(
                event.player.getGameProfile()
                    .getId()));
    }

    private PositionRow describeRow(EntityPlayerMP player, WorldSettings.GameType gameType) {
        final double TENTHS = 10.0;
        final int HALF_TURN = 180;
        // Minecraft does not wrap rotationYaw. The value passes 360 as the player turns
        final int roundedYaw = Math.round(MathHelper.wrapAngleTo180_float(player.rotationYaw));
        return new PositionRow(
            playerNumbers.numberFor(
                player.getGameProfile()
                    .getId()),
            describeMode(gameType),
            player.dimension,
            (int) Math.round(player.posX * TENTHS),
            (int) Math.round(player.posY * TENTHS),
            (int) Math.round(player.posZ * TENTHS),
            // a yaw of 179.5 or more rounds to 180, the facing of -180
            roundedYaw == HALF_TURN ? -HALF_TURN : roundedYaw,
            Math.round(player.rotationPitch));
    }

    private static String describeMode(WorldSettings.GameType gameType) {
        return switch (gameType.getName()) {
            case "survival" -> "s";
            case "creative" -> "c";
            case "adventure" -> "a";
            // NOT_SET has the name "". Et Futurum Requiem adds a spectator constant with the name "spectator"
            default -> gameType.getName();
        };
    }

    /** The sample intervals in server ticks, each at least 1. */
    public record Intervals(long survivalTicks, long otherTicks) {

        public static Intervals ofSeconds(int survivalSeconds, int otherSeconds) {
            final long TICKS_PER_SECOND = 20;
            return new Intervals(survivalSeconds * TICKS_PER_SECOND, otherSeconds * TICKS_PER_SECOND);
        }

        public boolean isDue(long tickCounter, WorldSettings.GameType gameType) {
            return tickCounter % ticksFor(gameType) == 0;
        }

        // Et Futurum Requiem adds a spectator constant to GameType, so every mode but survival takes otherTicks.
        // NOT_SET plays as survival. A FakePlayer has NOT_SET. A real player gets the server's mode at login
        public long ticksFor(WorldSettings.GameType gameType) {
            final boolean survival = gameType == WorldSettings.GameType.SURVIVAL
                || gameType == WorldSettings.GameType.NOT_SET;
            return survival ? survivalTicks : otherTicks;
        }
    }
}
