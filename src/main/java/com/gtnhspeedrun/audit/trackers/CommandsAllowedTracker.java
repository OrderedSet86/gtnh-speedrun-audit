package com.gtnhspeedrun.audit.trackers;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import net.minecraft.server.MinecraftServer;

import org.jetbrains.annotations.Nullable;

import com.google.gson.JsonObject;
import com.gtnhspeedrun.audit.core.AuditLogger;
import com.gtnhspeedrun.audit.mixins.minecraft.ServerConfigurationManagerAccessor;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/**
 * Log who may use commands at the session start, each change, polled every tick, and each opening of a singleplayer
 * world to LAN.
 *
 * <p>
 * Who may use commands depends on two settings. {@code commandsAllowed} is the "Allow Cheats" choice of a singleplayer
 * world, from level.dat: with the choice on, the world's owner may use commands. {@code commandsAllowedForAll} lets
 * every player use commands. Open to LAN with "Allow Cheats" sets {@code commandsAllowedForAll}, and 1.7.10 fires no
 * event for either setting.
 */
public final class CommandsAllowedTracker {

    // static: the IntegratedServer mixin has no tracker instance. The client thread adds entries, and the server thread
    // removes the entries
    private static final Queue<JsonObject> PENDING_LAN_OPENS = new ConcurrentLinkedQueue<>();

    private final AuditLogger logger;
    private final MinecraftServer server;
    private boolean lastAllowed;
    private boolean lastAllowedForAll;

    public CommandsAllowedTracker(AuditLogger logger, MinecraftServer server) {
        this.logger = logger;
        this.server = server;
        this.lastAllowed = isCommandsAllowed(server);
        this.lastAllowedForAll = isCommandsAllowedForAll(server);
        final JsonObject data = new JsonObject();
        data.addProperty("commandsAllowed", lastAllowed);
        data.addProperty("commandsAllowedForAll", lastAllowedForAll);
        logger.log("commands_allowed", data);
    }

    private static boolean isCommandsAllowed(MinecraftServer server) {
        return server.worldServers[0].getWorldInfo()
            .areCommandsAllowed();
    }

    private static boolean isCommandsAllowedForAll(MinecraftServer server) {
        return ((ServerConfigurationManagerAccessor) server.getConfigurationManager()).audit$isCommandsAllowedForAll();
    }

    /**
     * Queue a {@code lan_open} line for the next server tick. The Open to LAN screen runs on the client thread, and the
     * log drops a line logged from that thread.
     */
    public static void reportLanOpen(String gameType, boolean allowCheats, @Nullable String port) {
        final JsonObject data = new JsonObject();
        data.addProperty("gameType", gameType);
        data.addProperty("allowCheats", allowCheats);
        data.addProperty("port", port);
        PENDING_LAN_OPENS.add(data);
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        for (JsonObject lanOpen = PENDING_LAN_OPENS.poll(); lanOpen != null; lanOpen = PENDING_LAN_OPENS.poll()) {
            logger.log("lan_open", lanOpen);
        }
        final boolean allowed = isCommandsAllowed(server);
        if (allowed != lastAllowed) {
            logChange("commandsAllowed", lastAllowed, allowed);
            lastAllowed = allowed;
        }
        final boolean allowedForAll = isCommandsAllowedForAll(server);
        if (allowedForAll != lastAllowedForAll) {
            logChange("commandsAllowedForAll", lastAllowedForAll, allowedForAll);
            lastAllowedForAll = allowedForAll;
        }
    }

    private void logChange(String setting, boolean from, boolean to) {
        final JsonObject data = new JsonObject();
        data.addProperty("setting", setting);
        data.addProperty("from", from);
        data.addProperty("to", to);
        logger.log("commands_allowed_change", data);
    }
}
