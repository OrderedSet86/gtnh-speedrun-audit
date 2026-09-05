package com.gtnhspeedrun.audit.compat;

import java.util.UUID;

import com.google.gson.JsonObject;
import com.gtnhspeedrun.audit.core.AuditLogger;

import betterquesting.api.events.QuestEvent;
import betterquesting.api.properties.NativeProps;
import betterquesting.api.questing.IQuest;
import betterquesting.questing.QuestDatabase;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;

/**
 * Quest completions for the milestone timeline. Only instantiated when BetterQuesting is loaded — this class
 * references BQ types directly.
 *
 * <p>
 * BQ's passive detector posts a COMPLETED QuestEvent every 60 player-ticks even when nothing completed (empty
 * ID set), so the empty check is load-bearing, and quest timestamps are inherently ±3 s.
 */
public final class BqQuestTracker {

    private final AuditLogger logger;

    public BqQuestTracker(AuditLogger logger) {
        this.logger = logger;
    }

    @SubscribeEvent
    public void onQuest(QuestEvent event) {
        if (event.getType() != QuestEvent.Type.COMPLETED || event.getQuestIDs()
            .isEmpty()
            || !FMLCommonHandler.instance()
                .getEffectiveSide()
                .isServer()) {
            return;
        }
        for (UUID questId : event.getQuestIDs()) {
            final JsonObject data = new JsonObject();
            data.addProperty("playerUuid", String.valueOf(event.getPlayerID()));
            data.addProperty("questId", questId.toString());
            data.addProperty("questName", questName(questId));
            logger.log("quest_complete", data);
        }
    }

    private static String questName(UUID questId) {
        try {
            final IQuest quest = QuestDatabase.INSTANCE.get(questId);
            return quest == null ? "?" : quest.getProperty(NativeProps.NAME);
        } catch (RuntimeException | LinkageError e) {
            return "?";
        }
    }
}
