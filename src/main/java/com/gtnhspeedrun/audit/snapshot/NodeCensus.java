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
import com.gtnhspeedrun.audit.core.AuditLogger;
import com.gtnhspeedrun.audit.core.JsonUtil;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/**
 * Periodic record of every Thaumcraft node, node jar and Ethereal Shard in loaded chunks, as each tile's own
 * full NBT — aspects, base aspects, node type and modifier all live there, so no Thaumcraft API is bound and a
 * Thaumcraft-less pack simply finds nothing. Two consecutive censuses tell a bee's slow refill from an edit's
 * jump (R2b), and a node that vanishes between them was jarred or broken.
 *
 * <p>
 * Loaded chunks only: a node in an unloaded chunk can't change, and appears again the next time its chunk is
 * loaded during a census. The walk is the loaded tile list, once per interval, on the server thread.
 */
public final class NodeCensus {

    private final AuditLogger logger;
    private final MinecraftServer server;
    private final Set<String> classes;
    private final long intervalTicks;
    private long tickCounter;

    public NodeCensus(AuditLogger logger, MinecraftServer server, String[] tileClasses, int minutes) {
        this.logger = logger;
        this.server = server;
        this.classes = new HashSet<>();
        for (String c : tileClasses) {
            if (!c.trim()
                .isEmpty()) {
                classes.add(c.trim());
            }
        }
        this.intervalTicks = minutes * 60L * 20L;
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || classes.isEmpty()) {
            return;
        }
        if (++tickCounter >= intervalTicks) {
            census("interval");
        }
    }

    /** Also wired to /audit snapshot. */
    public void census(String trigger) {
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
            for (TileEntity te : tiles) {
                if (te == null || !classes.contains(
                    te.getClass()
                        .getName())) {
                    continue;
                }
                final JsonObject o = new JsonObject();
                o.addProperty("dim", world.provider.dimensionId);
                o.addProperty("x", te.xCoord);
                o.addProperty("y", te.yCoord);
                o.addProperty("z", te.zCoord);
                o.addProperty(
                    "class",
                    te.getClass()
                        .getName());
                try {
                    final NBTTagCompound tag = new NBTTagCompound();
                    te.writeToNBT(tag);
                    o.addProperty("nbtB64", JsonUtil.nbtToB64(tag));
                } catch (RuntimeException e) {
                    o.addProperty("nbtError", String.valueOf(e));
                }
                nodes.add(o);
            }
        }
        final JsonObject ref = new JsonObject();
        ref.addProperty("trigger", trigger);
        ref.addProperty("nodes", nodes.size());
        ref.addProperty("worlds", worlds);
        final JsonObject file = new JsonObject();
        file.add("nodes", nodes);
        logger.logSnapshot(
            "node_census",
            String.format(
                "nodes-%d.json.gz",
                logger.clock()
                    .get()),
            () -> file,
            ref);
    }
}
