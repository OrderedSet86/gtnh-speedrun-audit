package com.gtnhspeedrun.audit.trackers;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import net.minecraft.entity.player.EntityPlayerMP;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.gtnhspeedrun.audit.GtnhSpeedrunAudit;
import com.gtnhspeedrun.audit.core.AuditLogger;
import com.gtnhspeedrun.audit.core.JsonUtil;
import com.gtnhspeedrun.audit.core.PlayerNumbers;
import com.gtnhspeedrun.audit.core.SessionManager;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;
import cpw.mods.fml.common.network.handshake.NetworkDispatcher;

/**
 * Log player joins and leaves, the input of team-size rules.
 *
 * <p>
 * The join line has the gamemode at login, so an offline NBT edit to creative is visible with no gamemode
 * command.
 */
public final class PlayerSessionTracker {

    private final AuditLogger logger;
    private final PlayerNumbers playerNumbers;
    private final boolean logIp;
    private final String[] flagClientMods;

    public PlayerSessionTracker(SessionManager session, boolean logIp, String[] flagClientMods) {
        this.logger = session.logger();
        this.playerNumbers = session.playerNumbers();
        this.logIp = logIp;
        this.flagClientMods = flagClientMods;
    }

    @SubscribeEvent
    public void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.player instanceof EntityPlayerMP player) {
            final JsonObject data = describePlayer(player, true);
            data.addProperty(
                "playerNumber",
                playerNumbers.numberFor(
                    player.getGameProfile()
                        .getId()));
            attachClientMods(player, data);
            logger.log("player_join", data);
        }
    }

    @SubscribeEvent
    public void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.player instanceof EntityPlayerMP player) {
            logger.log("player_leave", describePlayer(player, false));
        }
    }

    /** Return the uuid, name, dimension, gamemode and block position, and the IP on a join with {@code logIp} set. */
    private JsonObject describePlayer(EntityPlayerMP player, boolean joining) {
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
        final JsonArray position = new JsonArray();
        position.add(JsonUtil.GSON.toJsonTree((int) player.posX));
        position.add(JsonUtil.GSON.toJsonTree((int) player.posY));
        position.add(JsonUtil.GSON.toJsonTree((int) player.posZ));
        data.add("pos", position);
        if (joining && logIp) {
            data.addProperty("ip", player.getPlayerIP());
        }
        return data;
    }

    /**
     * Add the hash of the client's handshake mod list, and the entries that match the {@code flagClientMods}
     * watchlist, to the join line. The full list is logged once per session for each player and list hash.
     *
     * <p>
     * The handshake list is the only view that a server-only mod has of client-side tools, such as Schematica's
     * printer or a WorldEdit client. The list comes from the client, so a modified client can send a false list. A
     * mod missing from the list is no evidence, and verifiers act only on a mod in the list.
     */
    private void attachClientMods(EntityPlayerMP player, JsonObject data) {
        // A FakePlayer or another player with no real connection has no net handler, or has a manager that never
        // opened a channel. Neither case had a handshake.
        Object dispatcher = null;
        try {
            if (player.playerNetServerHandler != null && player.playerNetServerHandler.netManager != null) {
                dispatcher = NetworkDispatcher.get(player.playerNetServerHandler.netManager);
            }
        } catch (RuntimeException ignored) { // noqa: NAR142, FML NetworkDispatcher.get() on an unconnected manager
            // channel() of an unconnected NetworkManager is null
        }
        if (dispatcher == null) {
            data.addProperty("clientMods", "none_reported");
            return;
        }
        final Map<String, String> mods = ClientModLists.readClientMods(dispatcher);
        if (mods == null) {
            // an integrated-server local channel or a vanilla client has no FML mod handshake
            data.addProperty("clientMods", "none_reported");
            return;
        }
        final List<String> sorted = new ArrayList<>(mods.size());
        for (Map.Entry<String, String> mod : mods.entrySet()) {
            sorted.add(mod.getKey() + "@" + mod.getValue());
        }
        Collections.sort(sorted);
        final int HASH_HEX_DIGITS = 16;
        final String hash = JsonUtil.sha256Hex(String.join("\n", sorted))
            .substring(0, HASH_HEX_DIGITS);
        data.addProperty("clientModCount", mods.size());
        data.addProperty("clientModsHash", hash);

        final JsonArray suspicious = new JsonArray();
        for (String entry : sorted) {
            final String lower = entry.toLowerCase(Locale.ROOT);
            for (String flagged : flagClientMods) {
                if (!flagged.isEmpty() && lower.contains(flagged.toLowerCase(Locale.ROOT))) {
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
        final SessionManager session = GtnhSpeedrunAudit.session();
        if (session != null && session.isFirstTime("clientmods:" + uuid + ":" + hash)) {
            final JsonObject full = new JsonObject();
            full.addProperty("uuid", uuid);
            full.addProperty("name", player.getCommandSenderName());
            full.addProperty("hash", hash);
            full.addProperty("count", mods.size());
            if (suspicious.size() > 0) {
                full.add("flagged", suspicious);
            }
            final JsonArray modArray = new JsonArray();
            for (String entry : sorted) {
                modArray.add(JsonUtil.GSON.toJsonTree(entry));
            }
            full.add("mods", modArray);
            logger.log("client_mods", full);
        }
    }
}
