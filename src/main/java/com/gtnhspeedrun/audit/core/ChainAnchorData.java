package com.gtnhspeedrun.audit.core;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;
import net.minecraft.world.World;
import net.minecraft.world.WorldSavedData;

/**
 * The in-world half of the tamper evidence. Saved with the overworld's map storage, so a backup restore rewinds
 * it together with the blocks: after a restore the external log's tail is ahead of this anchor, which is exactly
 * the signal the verifier reports as WORLD_ROLLBACK. The run clock and the milestone dedupe sets live here for
 * the same reason — they should rewind when the world does.
 */
public final class ChainAnchorData extends WorldSavedData {

    public static final String KEY = "gtnhspeedrunaudit_anchor";

    public String worldAuditUuid = "";
    public long lastSeq = -1;
    public String lastHash = "";
    public long cumulativeTicks;
    public long lastWallMs;
    public boolean cleanShutdown = true;

    public final Set<String> visitedDims = new HashSet<>();
    public final Set<String> seenKeyItems = new HashSet<>();
    public final Set<String> earnedAchievements = new HashSet<>();
    public final Set<String> formedMultiblocks = new HashSet<>();

    public ChainAnchorData(String key) {
        super(key);
    }

    public static ChainAnchorData get(World overworld) {
        ChainAnchorData data = (ChainAnchorData) overworld.mapStorage.loadData(ChainAnchorData.class, KEY);
        if (data == null) {
            data = new ChainAnchorData(KEY);
            data.worldAuditUuid = UUID.randomUUID().toString();
            overworld.mapStorage.setData(KEY, data);
            data.markDirty();
        }
        return data;
    }

    @Override
    public void readFromNBT(NBTTagCompound nbt) {
        worldAuditUuid = nbt.getString("worldAuditUuid");
        lastSeq = nbt.getLong("lastSeq");
        lastHash = nbt.getString("lastHash");
        cumulativeTicks = nbt.getLong("cumulativeTicks");
        lastWallMs = nbt.getLong("lastWallMs");
        cleanShutdown = nbt.getBoolean("cleanShutdown");
        readSet(nbt, "visitedDims", visitedDims);
        readSet(nbt, "seenKeyItems", seenKeyItems);
        readSet(nbt, "earnedAchievements", earnedAchievements);
        readSet(nbt, "formedMultiblocks", formedMultiblocks);
    }

    @Override
    public void writeToNBT(NBTTagCompound nbt) {
        nbt.setString("worldAuditUuid", worldAuditUuid);
        nbt.setLong("lastSeq", lastSeq);
        nbt.setString("lastHash", lastHash);
        nbt.setLong("cumulativeTicks", cumulativeTicks);
        nbt.setLong("lastWallMs", lastWallMs);
        nbt.setBoolean("cleanShutdown", cleanShutdown);
        writeSet(nbt, "visitedDims", visitedDims);
        writeSet(nbt, "seenKeyItems", seenKeyItems);
        writeSet(nbt, "earnedAchievements", earnedAchievements);
        writeSet(nbt, "formedMultiblocks", formedMultiblocks);
    }

    private static void readSet(NBTTagCompound nbt, String key, Set<String> into) {
        into.clear();
        final NBTTagList list = nbt.getTagList(key, 8); // 8 = string
        for (int i = 0; i < list.tagCount(); i++) {
            into.add(list.getStringTagAt(i));
        }
    }

    private static void writeSet(NBTTagCompound nbt, String key, Set<String> set) {
        final NBTTagList list = new NBTTagList();
        for (String s : set) {
            list.appendTag(new NBTTagString(s));
        }
        nbt.setTag(key, list);
    }
}
