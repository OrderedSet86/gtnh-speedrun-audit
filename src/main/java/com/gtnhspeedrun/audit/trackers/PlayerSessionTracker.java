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
    private final String[] flagClientMods;

    public PlayerSessionTracker(AuditLogger logger, boolean logIp, String[] flagClientMods) {
        this.logger = logger;
        this.logIp = logIp;
        this.flagClientMods = flagClientMods;
    }

    @SubscribeEvent
    public void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.player instanceof EntityPlayerMP player) {
            final JsonObject data = describe(player, true);
            attachClientMods(player, data);
            logger.log("player_join", data);
        }
    }

    /**
     * The client's self-reported handshake mod list — the only visibility a server-only mod has into
     * client-side tooling like Schematica's printer or a WorldEdit client. Self-reported: a modified client
     * can lie, so absence proves nothing; presence is the honest-runner signal verifiers act on. The full
     * list is logged once per (player, list-hash); joins carry the hash and any watchlist matches.
     */
    private void attachClientMods(EntityPlayerMP player, JsonObject data) {
        // FakePlayers (and anything else without a real connection) have no net handler, or one whose
        // manager never opened a channel — either way there was no handshake.
        Object dispatcher = null;
        try {
            if (player.playerNetServerHandler != null && player.playerNetServerHandler.netManager != null) {
                dispatcher = cpw.mods.fml.common.network.handshake.NetworkDispatcher
                    .get(player.playerNetServerHandler.netManager);
            }
        } catch (RuntimeException ignored) {
            // Unconnected NetworkManager: channel() is null.
        }
        if (dispatcher == null) {
            data.addProperty("clientMods", "none_reported");
            return;
        }
        final java.util.Map<String, String> mods = AuditSinks.takeClientMods(dispatcher);
        if (mods == null) {
            // Integrated-server local channel or vanilla client: no FML mod handshake happened.
            data.addProperty("clientMods", "none_reported");
            return;
        }
        final java.util.List<String> sorted = new java.util.ArrayList<>(mods.size());
        for (java.util.Map.Entry<String, String> e : mods.entrySet()) {
            sorted.add(e.getKey() + "@" + e.getValue());
        }
        java.util.Collections.sort(sorted);
        final String hash = JsonUtil.sha256Hex(String.join("\n", sorted))
            .substring(0, 16);
        data.addProperty("clientModCount", mods.size());
        data.addProperty("clientModsHash", hash);

        final JsonArray suspicious = new JsonArray();
        for (String entry : sorted) {
            final String lower = entry.toLowerCase(java.util.Locale.ROOT);
            for (String flagged : flagClientMods) {
                if (!flagged.isEmpty() && lower.contains(flagged.toLowerCase(java.util.Locale.ROOT))) {
                    suspicious.add(JsonUtil.GSON.toJsonTree(entry));
                    break;
                }
            }
        }
        if (suspicious.size() > 0) {
            data.add("flaggedClientMods", suspicious);
        }

        final String uuid = player.getGameProfile()
            .getId()
            .toString();
        final com.gtnhspeedrun.audit.core.SessionManager session = com.gtnhspeedrun.audit.GtnhSpeedrunAudit.session();
        if (session != null && session.once("clientmods:" + uuid + ":" + hash)) {
            final JsonObject full = new JsonObject();
            full.addProperty("uuid", uuid);
            full.addProperty("name", player.getCommandSenderName());
            full.addProperty("hash", hash);
            full.addProperty("count", mods.size());
            if (suspicious.size() > 0) {
                full.add("flagged", suspicious);
            }
            final JsonArray arr = new JsonArray();
            for (String entry : sorted) {
                arr.add(JsonUtil.GSON.toJsonTree(entry));
            }
            full.add("mods", arr);
            logger.log("client_mods", full);
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
