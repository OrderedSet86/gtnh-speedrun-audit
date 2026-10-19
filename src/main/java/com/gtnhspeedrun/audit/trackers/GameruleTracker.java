package com.gtnhspeedrun.audit.trackers;

import java.util.Map;
import java.util.TreeMap;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.GameRules;

import com.google.gson.JsonObject;
import com.gtnhspeedrun.audit.core.AuditLogger;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/**
 * Log each change of a game rule, polled every tick.
 *
 * <p>
 * Game rules change through /gamerule, the ServerUtilities config GUI, and mods that write the rules directly. No
 * event fires on all of these paths, so the tracker polls. The rule set is a couple of dozen strings, so a comparison
 * every tick costs little. {@code session_start} has the values at boot. For a /gamerule change, the {@code command}
 * line just before the change has the player who made the change.
 */
public final class GameruleTracker {

    private final AuditLogger logger;
    private final MinecraftServer server;
    private Map<String, String> lastSeen;

    public GameruleTracker(AuditLogger logger, MinecraftServer server) {
        this.logger = logger;
        this.server = server;
        this.lastSeen = readRules(server.worldServers[0].getGameRules());
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        final Map<String, String> now = readRules(server.worldServers[0].getGameRules());
        if (now.equals(lastSeen)) {
            return;
        }
        for (Map.Entry<String, String> change : diff(lastSeen, now).entrySet()) {
            final JsonObject data = new JsonObject();
            data.addProperty("rule", change.getKey());
            data.addProperty("from", lastSeen.get(change.getKey()));
            data.addProperty("to", now.get(change.getKey()));
            logger.log("gamerule_change", data);
        }
        lastSeen = now;
    }

    private static Map<String, String> readRules(GameRules rules) {
        final Map<String, String> out = new TreeMap<>();
        for (String rule : rules.getRules()) {
            out.put(rule, rules.getGameRuleStringValue(rule));
        }
        return out;
    }

    /**
     * Return each rule whose value differs, mapped to the rule's value in {@code after}, or to null when absent there.
     */
    public static Map<String, String> diff(Map<String, String> before, Map<String, String> after) {
        final Map<String, String> out = new TreeMap<>();
        for (Map.Entry<String, String> entry : after.entrySet()) {
            if (!entry.getValue()
                .equals(before.get(entry.getKey()))) {
                out.put(entry.getKey(), entry.getValue());
            }
        }
        for (String rule : before.keySet()) {
            if (!after.containsKey(rule)) {
                out.put(rule, null);
            }
        }
        return out;
    }
}
