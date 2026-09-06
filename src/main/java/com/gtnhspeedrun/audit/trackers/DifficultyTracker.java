package com.gtnhspeedrun.audit.trackers;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.EnumDifficulty;

import com.google.gson.JsonObject;
import com.gtnhspeedrun.audit.core.AuditLogger;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/**
 * 1.7.10 has no /difficulty command — changes come from the singleplayer options menu (no command, no
 * event) or offline server.properties edits. The poll catches the former the tick it happens; the latter
 * shows as a delta against the difficulty recorded in session_start. Peaceful clears hostile mobs on the
 * spot, so even a one-second dip matters to a board that bans it.
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
