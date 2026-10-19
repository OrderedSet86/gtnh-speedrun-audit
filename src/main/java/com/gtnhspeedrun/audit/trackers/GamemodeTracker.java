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
 * Log each gamemode change of an online player, and take an inventory snapshot at each change.
 *
 * <p>
 * 1.7.10 has no gamemode-change event, so the tracker polls every tick. The poll reads one field per online player.
 * A change into creative is the most common cheat, and the snapshot has the player's inventories at the change.
 * {@code lastSeen} has an entry only for a player seen by an earlier poll, so the first poll after a login logs no
 * change. {@link PlayerSessionTracker} logs the gamemode at login.
 */
public final class GamemodeTracker {

    private final AuditLogger logger;
    private final InventorySnapshotter snapshotter;
    private final MinecraftServer server;
    // an entry of a logged-off player stays until the session ends. Each entry is a UUID and an enum
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
        for (Object rawPlayer : server.getConfigurationManager().playerEntityList) {
            final EntityPlayerMP player = (EntityPlayerMP) rawPlayer;
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
    }
}
