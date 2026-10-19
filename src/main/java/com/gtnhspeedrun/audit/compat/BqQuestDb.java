package com.gtnhspeedrun.audit.compat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.server.MinecraftServer;

import com.google.gson.JsonObject;
import com.gtnhspeedrun.audit.core.AuditLogger;
import com.gtnhspeedrun.audit.core.JsonUtil;

import betterquesting.questing.QuestDatabase;
import betterquesting.questing.QuestLineDatabase;

/**
 * Log a fingerprint of the quest book after BetterQuesting loads the quest book. A database swapped between sessions
 * then shows as a hash change, with no diff of world downloads.
 *
 * <p>
 * The line has two hashes. {@code fileSha256} is the SHA-256 of the quest database file on disk at server start,
 * and a world that loaded the pack defaults has no such file. {@code liveHash} is the hash of the database in
 * memory, after BQ ran the logic that loads the defaults. Edits made while the server runs are in the
 * {@code quest_edit} lines. Use the class only when BetterQuesting is loaded.
 */
public final class BqQuestDb {

    private BqQuestDb() {}

    /**
     * Write one {@code quest_db} line. Add {@code liveError} when the live database can't be read, {@code fileError}
     * when the file can't be read, and {@code fileMissing} when there is no file.
     */
    public static void log(AuditLogger logger, MinecraftServer server) {
        final JsonObject data = new JsonObject();
        try {
            final NBTTagCompound live = new NBTTagCompound();
            live.setTag("quests", QuestDatabase.INSTANCE.writeToNBT(new NBTTagList(), null));
            live.setTag("lines", QuestLineDatabase.INSTANCE.writeToNBT(new NBTTagList(), null));
            data.addProperty("questCount", QuestDatabase.INSTANCE.size());
            data.addProperty("questLineCount", QuestLineDatabase.INSTANCE.size());
            data.addProperty("liveHash", JsonUtil.canonicalNbtHash(live));
        } catch (RuntimeException | LinkageError e) { // noqa: NAR142, degrade and report over BQ database code
            data.addProperty("liveError", String.valueOf(e));
        }
        final Path questDbPath = server.getFile(server.getFolderName())
            .toPath()
            .resolve("betterquesting")
            .resolve("QuestDatabase.json");
        if (Files.isRegularFile(questDbPath)) {
            try {
                final byte[] questDbBytes = Files.readAllBytes(questDbPath);
                data.addProperty("fileSha256", JsonUtil.sha256Hex(questDbBytes));
                data.addProperty("fileBytes", questDbBytes.length);
            } catch (IOException e) {
                data.addProperty("fileError", String.valueOf(e));
            }
        } else {
            data.addProperty("fileMissing", true);
        }
        logger.log("quest_db", data);
    }
}
