package com.gtnhspeedrun.audit.trackers;

import net.minecraft.entity.player.EntityPlayerMP;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.gtnhspeedrun.audit.core.AuditLogger;
import com.gtnhspeedrun.audit.core.JsonUtil;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;

/**
 * Join/leave ledger — team-size rules read from this. The gamemode at login is logged too: an offline NBT edit
 * to creative shows up here even though no gamemode command was ever issued.
 */
public final class PlayerSessionTracker {

    private final AuditLogger logger;
    private final boolean logIp;

    public PlayerSessionTracker(AuditLogger logger, boolean logIp) {
        this.logger = logger;
        this.logIp = logIp;
    }

    @SubscribeEvent
    public void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.player instanceof EntityPlayerMP player) {
            logger.log("player_join", describe(player, true));
        }
    }

    @SubscribeEvent
    public void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.player instanceof EntityPlayerMP player) {
            logger.log("player_leave", describe(player, false));
        }
    }

    private JsonObject describe(EntityPlayerMP player, boolean joining) {
        final JsonObject data = new JsonObject();
        data.addProperty(
            "uuid",
            player.getGameProfile()
                .getId()
                .toString());
        data.addProperty("name", player.getCommandSenderName());
        data.addProperty("dim", player.dimension);
        data.addProperty(
            "gamemode",
            player.theItemInWorldManager.getGameType()
                .getName());
        final JsonArray pos = new JsonArray();
        pos.add(JsonUtil.GSON.toJsonTree((int) player.posX));
        pos.add(JsonUtil.GSON.toJsonTree((int) player.posY));
        pos.add(JsonUtil.GSON.toJsonTree((int) player.posZ));
        data.add("pos", pos);
        if (joining && logIp) {
            data.addProperty("ip", player.getPlayerIP());
        }
        return data;
    }
}
