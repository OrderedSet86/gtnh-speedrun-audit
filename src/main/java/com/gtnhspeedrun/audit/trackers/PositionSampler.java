package com.gtnhspeedrun.audit.trackers;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.WorldSettings;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.gtnhspeedrun.audit.core.AuditLogger;
import com.gtnhspeedrun.audit.snapshot.PlayerPlace;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/**
 * Log the place and facing of each online player at a fixed interval, with a shorter interval outside survival.
 *
 * <p>
 * Each {@code pos_sample} line has every player due on that tick. A player is due on each multiple of the interval of
 * the player's gamemode, so a player who changes into creative is sampled within the shorter interval. The intervals
 * are in server ticks, so sampling stops while an integrated server is paused.
 */
public final class PositionSampler {

    private final AuditLogger logger;
    private final MinecraftServer server;
    private final Intervals intervals;
    private long tickCounter;

    public PositionSampler(AuditLogger logger, MinecraftServer server, Intervals intervals) {
        this.logger = logger;
        this.server = server;
        this.intervals = intervals;
    }

    /** Log one {@code pos_sample} line on a tick with at least one player due, and no line on any other tick. */
    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        tickCounter++;
        // null until the first due player: most ticks have no player due, and then make no allocation
        JsonArray players = null;
        for (Object rawPlayer : server.getConfigurationManager().playerEntityList) {
            final EntityPlayerMP player = (EntityPlayerMP) rawPlayer;
            final WorldSettings.GameType gameType = player.theItemInWorldManager.getGameType();
            if (intervals.isDue(tickCounter, gameType)) {
                if (players == null) {
                    players = new JsonArray();
                }
                final JsonObject sample = new JsonObject();
                sample.addProperty(
                    "uuid",
                    player.getGameProfile()
                        .getId()
                        .toString());
                sample.addProperty("name", player.getCommandSenderName());
                sample.addProperty("gamemode", gameType.getName());
                PlayerPlace.addPlace(sample, player);
                players.add(sample);
            }
        }
        if (players != null) {
            final JsonObject data = new JsonObject();
            data.add("players", players);
            logger.log("pos_sample", data);
        }
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
