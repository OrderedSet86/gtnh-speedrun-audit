package com.gtnhspeedrun.audit.compat;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.server.MinecraftServer;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.gtnhspeedrun.audit.GtnhSpeedrunAudit;
import com.gtnhspeedrun.audit.core.JsonUtil;
import com.gtnhspeedrun.audit.core.SessionManager;

import betterquesting.api.properties.NativeProps;
import betterquesting.api.questing.IQuest;
import betterquesting.api.questing.tasks.ITask;
import betterquesting.api2.storage.DBEntry;
import betterquesting.questing.QuestDatabase;

/**
 * Record who completed a party quest. BQ marks a task complete for every party member at once, and the COMPLETED
 * event then fires for each member, offline members included. The member whose tick or button press ran the
 * detection is the {@code ParticipantInfo.PLAYER} that BQ built just before the detection, on the same thread and
 * in the same tick. The BQ mixins pass that participant to this class.
 *
 * <p>
 * The hand-off is a thread-local stamped with the server tick. For a task completion with no participant from the
 * current tick, such as an edit-mode or admin completion, the line has {@code actorUuid: null} and no participant
 * from an earlier tick. Use the class only when BetterQuesting is loaded. Every BQ API used here is the same from
 * 3.7.15 (pack 2.8.4) through 3.8.87.
 */
public final class BqActor {

    private static final int MAX_PAYLOAD_B64 = 65536;

    private static final ThreadLocal<Participant> CURRENT = new ThreadLocal<>();

    private BqActor() {}

    /** RETURN of the {@code ParticipantInfo} constructor: this player is about to run a detection. */
    public static void noteParticipant(EntityPlayer player, UUID questingUuid, List<UUID> active) {
        if (!(player != null && player.worldObj != null && !player.worldObj.isRemote)) {
            return;
        }
        CURRENT.set(new Participant(questingUuid, player.getCommandSenderName(), active, readServerTick()));
    }

    /** HEAD of {@code TaskBase.setComplete}, only when the task was not complete for {@code uuid} yet. */
    public static void taskCompleting(ITask task, UUID uuid) {
        final SessionManager session = GtnhSpeedrunAudit.session();
        if (session == null) {
            return;
        }
        final JsonObject data = new JsonObject();
        data.addProperty("playerUuid", String.valueOf(uuid));
        data.addProperty(
            "taskType",
            task.getClass()
                .getSimpleName());
        addTaskLocation(task, data);
        final Participant participant = CURRENT.get();
        if (participant != null && participant.tick() == readServerTick()) {
            data.addProperty("actorUuid", String.valueOf(participant.uuid()));
            data.addProperty("actorName", participant.name());
            final JsonArray active = new JsonArray();
            for (UUID activeUuid : participant.active()) {
                active.add(JsonUtil.GSON.toJsonTree(activeUuid.toString()));
            }
            data.add("activeUuids", active);
        } else {
            data.add("actorUuid", JsonNull.INSTANCE);
        }
        session.logger()
            .log("quest_task_complete", data);
    }

    /**
     * HEAD of the server handlers that change the quest book: quest edits, chapter edits, imports, and settings such
     * as the edit-mode toggle. BQ refuses an edit packet from a player who is not an op. The {@code quest_edit} line
     * is written either way.
     */
    public static void edit(String handler, NBTTagCompound payload, EntityPlayer sender) {
        final SessionManager session = GtnhSpeedrunAudit.session();
        if (session == null) {
            return;
        }
        final JsonObject data = new JsonObject();
        data.addProperty("handler", handler);
        if (sender != null) {
            data.addProperty(
                "uuid",
                sender.getGameProfile()
                    .getId()
                    .toString());
            data.addProperty("name", sender.getCommandSenderName());
        }
        if (payload != null) {
            if (payload.hasKey("action")) {
                data.addProperty("action", payload.getInteger("action"));
            }
            data.addProperty("payloadHash", JsonUtil.canonicalNbtHash(payload));
            final String b64 = JsonUtil.nbtToB64(payload);
            if (b64.length() <= MAX_PAYLOAD_B64) {
                data.addProperty("payloadB64", b64);
            } else {
                data.addProperty("payloadB64Chars", b64.length());
            }
        }
        session.logger()
            .log("quest_edit", data);
    }

    /**
     * Add the quest and the task index of {@code task}. The lookup is a linear scan over every quest, and task
     * completions are rare.
     */
    private static void addTaskLocation(ITask task, JsonObject into) {
        try {
            for (Map.Entry<UUID, IQuest> quest : QuestDatabase.INSTANCE.entrySet()) {
                for (DBEntry<ITask> entry : quest.getValue()
                    .getTasks()
                    .getEntries()) {
                    if (entry.getValue() == task) {
                        into.addProperty(
                            "questId",
                            quest.getKey()
                                .toString());
                        into.addProperty(
                            "questName",
                            JsonUtil.stripFormatting(
                                quest.getValue()
                                    .getProperty(NativeProps.NAME)));
                        into.addProperty("taskIndex", entry.getID());
                        return;
                    }
                }
            }
        } catch (RuntimeException | LinkageError ignored) { // noqa: NAR142, degrade and report over BQ database code
            // the line still has the player and the task type
        }
    }

    private static int readServerTick() {
        final MinecraftServer server = MinecraftServer.getServer();
        return server == null ? -1 : server.getTickCounter();
    }

    // no copy of active. BQ passes ACTIVE_UUIDS as an unmodifiable list, and List.copyOf() would throw inside the BQ
    // constructor on a null element.
    private record Participant(UUID uuid, String name, List<UUID> active, int tick) {}
}
