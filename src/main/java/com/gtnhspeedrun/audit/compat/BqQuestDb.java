package com.gtnhspeedrun.audit.compat;

import java.io.File;
import java.nio.file.Files;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.server.MinecraftServer;

import com.google.gson.JsonObject;
import com.gtnhspeedrun.audit.core.AuditLogger;
import com.gtnhspeedrun.audit.core.JsonUtil;

import betterquesting.questing.QuestDatabase;
import betterquesting.questing.QuestLineDatabase;

/**
 * Fingerprints the quest book once BetterQuesting has loaded it, so a database swapped between sessions shows as
 * a hash change instead of needing a diff of world downloads. Two hashes because they answer different things:
 * the file is what was on disk at boot (absent on a world that loaded the pack defaults), the in-memory hash is
 * what is actually live after BQ's own default-loading logic ran. Edits made while running are logged separately
 * (quest_edit). Only instantiated when BetterQuesting is loaded.
 */
public final class BqQuestDb {

    private BqQuestDb() {}

    public static void log(AuditLogger logger, MinecraftServer server) {
        final JsonObject data = new JsonObject();
        try {
            final NBTTagCompound live = new NBTTagCompound();
            live.setTag("quests", QuestDatabase.INSTANCE.writeToNBT(new NBTTagList(), null));
            live.setTag("lines", QuestLineDatabase.INSTANCE.writeToNBT(new NBTTagList(), null));
            data.addProperty("questCount", QuestDatabase.INSTANCE.size());
            data.addProperty("questLineCount", QuestLineDatabase.INSTANCE.size());
            data.addProperty("liveHash", JsonUtil.canonicalNbtHash(live));
        } catch (RuntimeException | LinkageError e) {
            data.addProperty("liveError", String.valueOf(e));
        }
        final File file = server.getFile(server.getFolderName() + "/betterquesting/QuestDatabase.json");
        if (file.isFile()) {
            try {
                final byte[] bytes = Files.readAllBytes(file.toPath());
                data.addProperty("fileSha256", JsonUtil.sha256Hex(bytes));
                data.addProperty("fileBytes", bytes.length);
            } catch (java.io.IOException e) {
                data.addProperty("fileError", String.valueOf(e));
            }
        } else {
            data.addProperty("fileMissing", true);
        }
        logger.log("quest_db", data);
    }
}
