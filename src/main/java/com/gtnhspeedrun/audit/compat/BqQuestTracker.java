package com.gtnhspeedrun.audit.compat;

import java.util.UUID;

import com.google.gson.JsonObject;
import com.gtnhspeedrun.audit.core.AuditLogger;
import com.gtnhspeedrun.audit.core.JsonUtil;

import betterquesting.api.events.QuestEvent;
import betterquesting.api.properties.NativeProps;
import betterquesting.api.questing.IQuest;
import betterquesting.questing.QuestDatabase;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;

/**
 * Log quest completions for the milestone timeline. The class references BQ types, so construct it only when
 * BetterQuesting is loaded.
 *
 * <p>
 * The BQ passive detector posts a COMPLETED {@code QuestEvent} every 60 player ticks, with an empty quest ID set
 * when no quest completed. The handler skips an empty set. A quest timestamp is accurate to ±3 s.
 */
public final class BqQuestTracker {

    private final AuditLogger logger;

    public BqQuestTracker(AuditLogger logger) {
        this.logger = logger;
    }

    @SubscribeEvent
    public void onQuest(QuestEvent event) {
        if (!(event.getType() == QuestEvent.Type.COMPLETED && !event.getQuestIDs()
            .isEmpty()
            && FMLCommonHandler.instance()
                .getEffectiveSide()
                .isServer())) {
            return;
        }
        for (UUID questId : event.getQuestIDs()) {
            final JsonObject data = new JsonObject();
            data.addProperty("playerUuid", String.valueOf(event.getPlayerID()));
            data.addProperty("questId", questId.toString());
            data.addProperty("questName", readQuestName(questId));
            logger.log("quest_complete", data);
        }
    }

    private static String readQuestName(UUID questId) {
        try {
            final IQuest quest = QuestDatabase.INSTANCE.get(questId);
            return quest == null ? "?" : JsonUtil.stripFormatting(quest.getProperty(NativeProps.NAME));
        } catch (RuntimeException | LinkageError e) { // noqa: NAR142, degrade and report over BQ database code
            return "?";
        }
    }
}
