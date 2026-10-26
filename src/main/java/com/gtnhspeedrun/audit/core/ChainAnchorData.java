package com.gtnhspeedrun.audit.core;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;
import net.minecraft.world.World;
import net.minecraft.world.WorldSavedData;
import net.minecraftforge.common.util.Constants;

/**
 * Store the in-world half of the tamper evidence in the map storage of the overworld. A backup restore rewinds the
 * anchor together with the blocks, and the tail of the external log is then ahead of the anchor. That difference is
 * the WORLD_ROLLBACK verdict. The run clocks and the milestone dedupe sets are in the anchor too, so a restore
 * rewinds them with the world.
 */
public final class ChainAnchorData extends WorldSavedData {

    public static final String KEY = "gtnhspeedrunaudit_anchor";

    public String worldAuditUuid = "";
    public long lastSeq = -1;
    public String lastHash = "";
    public long cumulativeTicks;
    public long lastWallMs;
    public boolean cleanShutdown = true;
    /**
     * The id of the session that last saved the world. After a crash, the field has the id of the crashed session.
     */
    public String sessionId = "";
    public boolean timingStarted = false;
    /** RTA counts from this wall-clock time. */
    public long timingStartWallMs;
    /** Ticks after timing start with at least one player connected. */
    public long cumulativeOnlineTicks;
    /** SHA-256 of the item ID table that was logged last. */
    public String idTableSha256 = "";
    /**
     * The world folder that {@link #worldAuditUuid} belongs to. A start in another folder is a world copy, and the
     * copy gets a new audit UUID.
     */
    public String homeFolder = "";

    public final Set<String> visitedDims = new HashSet<>();
    public final Set<String> seenKeyItems = new HashSet<>();
    public final Set<String> earnedAchievements = new HashSet<>();
    public final Set<String> formedMultiblocks = new HashSet<>();

    public ChainAnchorData(String key) {
        super(key);
    }

    public static ChainAnchorData loadOrCreate(World overworld) {
        ChainAnchorData data = (ChainAnchorData) overworld.mapStorage.loadData(ChainAnchorData.class, KEY);
        if (data == null) {
            data = new ChainAnchorData(KEY);
            data.worldAuditUuid = UUID.randomUUID()
                .toString();
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
        sessionId = nbt.getString("sessionId");
        // worlds audited before the timingStarted tag existed have ticks on the clock but no tag
        timingStarted = nbt.getBoolean("timingStarted") || cumulativeTicks > 0;
        timingStartWallMs = nbt.getLong("timingStartWallMs");
        cumulativeOnlineTicks = nbt.getLong("cumulativeOnlineTicks");
        idTableSha256 = nbt.getString("idTableSha256");
        // a world saved before the homeFolder tag existed has no tag. getString() returns "" for a missing tag
        homeFolder = nbt.getString("homeFolder");
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
        nbt.setString("sessionId", sessionId);
        nbt.setBoolean("timingStarted", timingStarted);
        nbt.setLong("timingStartWallMs", timingStartWallMs);
        nbt.setLong("cumulativeOnlineTicks", cumulativeOnlineTicks);
        nbt.setString("idTableSha256", idTableSha256);
        nbt.setString("homeFolder", homeFolder);
        writeSet(nbt, "visitedDims", visitedDims);
        writeSet(nbt, "seenKeyItems", seenKeyItems);
        writeSet(nbt, "earnedAchievements", earnedAchievements);
        writeSet(nbt, "formedMultiblocks", formedMultiblocks);
    }

    private static void readSet(NBTTagCompound nbt, String key, Set<String> values) {
        values.clear();
        final NBTTagList list = nbt.getTagList(key, Constants.NBT.TAG_STRING);
        for (int i = 0; i < list.tagCount(); i++) {
            values.add(list.getStringTagAt(i));
        }
    }

    private static void writeSet(NBTTagCompound nbt, String key, Set<String> values) {
        final NBTTagList list = new NBTTagList();
        for (String value : values) {
            list.appendTag(new NBTTagString(value));
        }
        nbt.setTag(key, list);
    }
}
