package com.gtnhspeedrun.audit.compat;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.server.MinecraftServer;

import com.google.gson.JsonArray;
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
 * Who actually completed a party quest. BQ marks a task complete for every party member at once, and its
 * COMPLETED event then fires per member — including members who were offline (R1). The member whose tick or
 * button press ran the detection is the {@code ParticipantInfo.PLAYER} built just before it, on the same thread,
 * in the same tick; the mixins hand that over here.
 *
 * <p>
 * The hand-off is a thread-local stamped with the server tick. A task completion with no participant from the
 * current tick (an edit-mode or admin completion) logs {@code actor: null} rather than a stale guess. Only
 * instantiated or called when BetterQuesting is loaded; every BQ API used here is identical from 3.7.15 (pack
 * 2.8.4) through 3.8.87.
 */
public final class BqActor {

    private static final int MAX_PAYLOAD_B64 = 65536;

    private static final class Participant {

        final UUID uuid;
        final String name;
        final List<UUID> active;
        final int tick;

        Participant(UUID uuid, String name, List<UUID> active, int tick) {
            this.uuid = uuid;
            this.name = name;
            this.active = active;
            this.tick = tick;
        }
    }

    private static final ThreadLocal<Participant> CURRENT = new ThreadLocal<>();

    private BqActor() {}

    /** RETURN of ParticipantInfo's constructor: this player is about to run a detection. */
    public static void noteParticipant(EntityPlayer player, UUID questingUuid, List<UUID> active) {
        if (player == null || player.worldObj == null || player.worldObj.isRemote) {
            return;
        }
        CURRENT.set(new Participant(questingUuid, player.getCommandSenderName(), active, tick()));
    }

    /** HEAD of TaskBase.setComplete, only when the task was not complete for {@code uuid} yet. */
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
        locate(task, data);
        final Participant p = CURRENT.get();
        if (p != null && p.tick == tick()) {
            data.addProperty("actorUuid", String.valueOf(p.uuid));
            data.addProperty("actorName", p.name);
            final JsonArray active = new JsonArray();
            for (UUID u : p.active) {
                active.add(JsonUtil.GSON.toJsonTree(u.toString()));
            }
            data.add("activeUuids", active);
        } else {
            data.add("actorUuid", com.google.gson.JsonNull.INSTANCE);
        }
        session.logger()
            .log("quest_task_complete", data);
    }

    /** HEAD of the server handlers that change the quest book: edits, chapters, imports, settings (edit mode). */
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
        // Edit packets are refused inside the handler for non-ops; this records the attempt either way.
        session.logger()
            .log("quest_edit", data);
    }

    /** Quest and task index holding this task instance; a linear scan, but completions are rare. */
    private static void locate(ITask task, JsonObject into) {
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
        } catch (RuntimeException | LinkageError ignored) {
            // The line still names the player and task type.
        }
    }

    private static int tick() {
        final MinecraftServer server = MinecraftServer.getServer();
        return server == null ? -1 : server.getTickCounter();
    }
}
