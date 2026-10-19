package com.gtnhspeedrun.audit.core;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.WorldServer;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.ModContainer;

/** The inputs of the session_start line, and the line's data built from them. */
public record SessionStartData(MinecraftServer server, ChainAnchorData anchor, ChainTail tail, String verifyVerdict,
    boolean previousCrashed) {

    /** Build the data of the session_start line. The run analyzer reads these keys, so NEVER rename one. */
    public JsonObject toJson() {
        final WorldServer overworld = server.worldServers[0];
        final JsonObject data = new JsonObject();
        data.addProperty("worldAuditUuid", anchor.worldAuditUuid);
        data.addProperty("verifyVerdict", verifyVerdict);
        data.addProperty("timingStarted", anchor.timingStarted);
        data.addProperty("timingStartWallMs", anchor.timingStartWallMs);
        data.addProperty("onlineTicks", anchor.cumulativeOnlineTicks);
        data.addProperty("previousCrashed", previousCrashed);
        data.addProperty("dedicated", server.isDedicatedServer());
        data.addProperty("worldFolder", server.getFolderName());
        data.addProperty("seed", overworld.getSeed());
        data.addProperty("difficulty", String.valueOf(overworld.difficultySetting));
        // Vanilla counts every world tick in worldTotalTime, and /time set does not change the count. For a world
        // adopted mid-run, worldTotalTime is the pre-audit IGT baseline. After the mod adopts the world,
        // worldTotalTime advances with the audit clock, so a difference between the two clocks is a cross-check.
        data.addProperty("worldTotalTime", overworld.getTotalWorldTime());
        data.addProperty("mcVersion", server.getMinecraftVersion());
        data.addProperty("javaVersion", System.getProperty("java.version"));
        data.addProperty(
            "modCount",
            Loader.instance()
                .getActiveModList()
                .size());

        data.add(
            "anchor",
            AnchorValues.readFrom(anchor)
                .toJson());

        final JsonObject tailObj = new JsonObject();
        tailObj.addProperty("seq", tail.seq());
        tailObj.addProperty("hash", tail.hash());
        data.add("logTail", tailObj);

        if ("WORLD_ROLLBACK".equals(verifyVerdict) || "CRASH_RECOVERY".equals(verifyVerdict)) {
            final JsonObject rollback = new JsonObject();
            rollback.addProperty("eventsRewound", tail.seq() - anchor.lastSeq);
            rollback.addProperty("wallGapMs", System.currentTimeMillis() - anchor.lastWallMs);
            data.add("rollback", rollback);
        }

        final JsonArray ops = new JsonArray();
        for (String op : server.getConfigurationManager()
            .func_152606_n()) {
            ops.add(JsonUtil.GSON.toJsonTree(op));
        }
        data.add("ops", ops);

        final JsonObject gamerules = new JsonObject();
        for (String rule : overworld.getGameRules()
            .getRules()) {
            gamerules.addProperty(
                rule,
                overworld.getGameRules()
                    .getGameRuleStringValue(rule));
        }
        data.add("gamerules", gamerules);

        final JsonArray mods = new JsonArray();
        for (ModContainer mod : Loader.instance()
            .getActiveModList()) {
            mods.add(JsonUtil.GSON.toJsonTree(mod.getModId() + "@" + mod.getVersion()));
        }
        data.add("mods", mods);
        return data;
    }
}
