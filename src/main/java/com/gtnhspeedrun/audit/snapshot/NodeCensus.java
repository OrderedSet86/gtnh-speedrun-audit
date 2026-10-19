package com.gtnhspeedrun.audit.snapshot;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.WorldServer;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.gtnhspeedrun.audit.AuditConfig;
import com.gtnhspeedrun.audit.core.AuditLogger;
import com.gtnhspeedrun.audit.core.JsonUtil;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/**
 * Record every Thaumcraft node, node jar and Ethereal Shard in loaded chunks at a fixed interval.
 *
 * <p>
 * Each entry is the tile's full NBT. Aspects, base aspects, node type and modifier are all in the NBT, so the class
 * calls no Thaumcraft API, and a pack with no Thaumcraft gets an empty census. In two consecutive censuses, a verifier
 * tells a bee's slow refill from the jump of an edit. A node that is gone in the second census was jarred or broken,
 * or its chunk is unloaded.
 *
 * <p>
 * The census reads only the loaded tile list of each world, on the server thread. A node in an unloaded chunk
 * doesn't change, and is in the next census taken while its chunk is loaded.
 */
public final class NodeCensus {

    private final AuditLogger logger;
    private final MinecraftServer server;
    private final Set<String> classes;
    private final long intervalTicks;
    private long tickCounter;

    /**
     * Read the fully-qualified tile entity class names from {@link AuditConfig#nodeCensusTiles}, with blank entries
     * skipped, and the interval in minutes from {@link AuditConfig#nodeCensusMinutes}.
     */
    public NodeCensus(AuditLogger logger, MinecraftServer server) {
        this.logger = logger;
        this.server = server;
        this.classes = new HashSet<>();
        for (String rawClass : AuditConfig.nodeCensusTiles) {
            if (!rawClass.trim()
                .isEmpty()) {
                classes.add(rawClass.trim());
            }
        }
        final long TICKS_PER_MINUTE = 60L * 20L;
        this.intervalTicks = AuditConfig.nodeCensusMinutes * TICKS_PER_MINUTE;
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || classes.isEmpty()) {
            return;
        }
        if (++tickCounter >= intervalTicks) {
            takeCensus("interval");
        }
    }

    /** Write a {@code node_census} snapshot file, and restart the interval. */
    public void takeCensus(String trigger) {
        tickCounter = 0;
        final JsonArray nodes = new JsonArray();
        int worlds = 0;
        for (WorldServer world : server.worldServers) {
            if (world == null) {
                continue;
            }
            worlds++;
            @SuppressWarnings("unchecked")
            final List<TileEntity> tiles = world.loadedTileEntityList;
            for (TileEntity tile : tiles) {
                if (!(tile != null && classes.contains(
                    tile.getClass()
                        .getName()))) {
                    continue;
                }
                final JsonObject node = new JsonObject();
                node.addProperty("dim", world.provider.dimensionId);
                node.addProperty("x", tile.xCoord);
                node.addProperty("y", tile.yCoord);
                node.addProperty("z", tile.zCoord);
                node.addProperty(
                    "class",
                    tile.getClass()
                        .getName());
                try {
                    final NBTTagCompound tag = new NBTTagCompound();
                    tile.writeToNBT(tag);
                    node.addProperty("nbtB64", JsonUtil.nbtToB64(tag));
                } catch (RuntimeException e) { // noqa: NAR142, degrade and report over Thaumcraft writeToNBT()
                    node.addProperty("nbtError", String.valueOf(e));
                }
                nodes.add(node);
            }
        }
        final JsonObject reference = new JsonObject();
        reference.addProperty("trigger", trigger);
        reference.addProperty("nodes", nodes.size());
        reference.addProperty("worlds", worlds);
        final JsonObject file = new JsonObject();
        file.add("nodes", nodes);
        logger.logSnapshot(
            new AuditLogger.Snapshot(
                "node_census",
                String.format(
                    "nodes-%d.json.gz",
                    logger.clock()
                        .get()),
                () -> file,
                reference));
    }
}
