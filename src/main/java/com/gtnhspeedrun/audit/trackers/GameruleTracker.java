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
 * Game rules change through /gamerule, the ServerUtilities config GUI and mods writing them directly, so a poll
 * is the one hook that sees all of them. The rule set is a couple of dozen strings — comparing it every tick is
 * cheaper than the events that would replace it. Values at boot are already in session_start; this logs the
 * edges in between. The /gamerule command line just before a change says who made it.
 */
public final class GameruleTracker {

    private final AuditLogger logger;
    private final MinecraftServer server;
    private Map<String, String> lastSeen;

    public GameruleTracker(AuditLogger logger, MinecraftServer server) {
        this.logger = logger;
        this.server = server;
        this.lastSeen = read(server.worldServers[0].getGameRules());
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        final Map<String, String> now = read(server.worldServers[0].getGameRules());
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

    static Map<String, String> read(GameRules rules) {
        final Map<String, String> out = new TreeMap<>();
        for (String rule : rules.getRules()) {
            out.put(rule, rules.getGameRuleStringValue(rule));
        }
        return out;
    }

    /** Rules whose value differs, including rules that appeared or vanished (value null on the missing side). */
    static Map<String, String> diff(Map<String, String> before, Map<String, String> after) {
        final Map<String, String> out = new TreeMap<>();
        for (Map.Entry<String, String> e : after.entrySet()) {
            if (!e.getValue()
                .equals(before.get(e.getKey()))) {
                out.put(e.getKey(), e.getValue());
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
