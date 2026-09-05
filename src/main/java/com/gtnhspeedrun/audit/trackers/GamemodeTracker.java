package com.gtnhspeedrun.audit.trackers;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.WorldSettings;

import com.google.gson.JsonObject;
import com.gtnhspeedrun.audit.core.AuditLogger;
import com.gtnhspeedrun.audit.snapshot.InventorySnapshotter;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/**
 * 1.7.10 has no gamemode-change event, so poll every tick — it's a field read per online player. Transitions
 * into creative are the #1 cheat vector; each one also triggers an inventory snapshot, so what was carried
 * across the boundary is on record. The login-state line comes from PlayerSessionTracker; this map only covers
 * players seen ticking.
 */
public final class GamemodeTracker {

    private final AuditLogger logger;
    private final InventorySnapshotter snapshotter;
    private final MinecraftServer server;
    private final Map<UUID, WorldSettings.GameType> lastSeen = new HashMap<>();

    public GamemodeTracker(AuditLogger logger, InventorySnapshotter snapshotter, MinecraftServer server) {
        this.logger = logger;
        this.snapshotter = snapshotter;
        this.server = server;
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        for (Object o : server.getConfigurationManager().playerEntityList) {
            final EntityPlayerMP player = (EntityPlayerMP) o;
            final UUID uuid = player.getGameProfile()
                .getId();
            final WorldSettings.GameType now = player.theItemInWorldManager.getGameType();
            final WorldSettings.GameType before = lastSeen.put(uuid, now);
            if (before != null && before != now) {
                final JsonObject data = new JsonObject();
                data.addProperty("uuid", uuid.toString());
                data.addProperty("name", player.getCommandSenderName());
                data.addProperty("from", before.getName());
                data.addProperty("to", now.getName());
                logger.log("gamemode_change", data);
                snapshotter.capture(player, "gamemode_change");
            }
        }
        // Entries for logged-off players are harmless (a UUID and an enum); cleared with the session.
    }
}
