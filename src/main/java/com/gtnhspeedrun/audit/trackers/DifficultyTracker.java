package com.gtnhspeedrun.audit.trackers;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.EnumDifficulty;

import com.google.gson.JsonObject;
import com.gtnhspeedrun.audit.core.AuditLogger;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/**
 * Log each change of the world difficulty, polled every tick.
 *
 * <p>
 * 1.7.10 fires no event for a difficulty change, and the singleplayer options menu changes the difficulty with no
 * command. A change is logged at the end of the tick of the change. An edit of server.properties while the server is
 * offline shows as a {@code session_start} difficulty that differs from the last difficulty of the session before.
 * Peaceful removes hostile mobs at once, so a one-second change matters to a leaderboard that bans Peaceful.
 */
public final class DifficultyTracker {

    private final AuditLogger logger;
    private final MinecraftServer server;
    private EnumDifficulty lastSeen;

    public DifficultyTracker(AuditLogger logger, MinecraftServer server) {
        this.logger = logger;
        this.server = server;
        this.lastSeen = server.worldServers[0].difficultySetting;
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        final EnumDifficulty now = server.worldServers[0].difficultySetting;
        if (now != lastSeen) {
            final JsonObject data = new JsonObject();
            data.addProperty("from", String.valueOf(lastSeen));
            data.addProperty("to", String.valueOf(now));
            logger.log("difficulty_change", data);
            lastSeen = now;
        }
    }
}
