package com.gtnhspeedrun.audit.compat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.block.Block;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.gtnhspeedrun.audit.core.AuditLogger;
import com.sk89q.jnbt.CompoundTag;
import com.sk89q.jnbt.Tag;
import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.Vector;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.WorldEditException;
import com.sk89q.worldedit.blocks.BaseBlock;
import com.sk89q.worldedit.event.extent.EditSessionEvent;
import com.sk89q.worldedit.extension.platform.Actor;
import com.sk89q.worldedit.extent.AbstractDelegateExtent;
import com.sk89q.worldedit.extent.Extent;
import com.sk89q.worldedit.forge.ForgeWorld;
import com.sk89q.worldedit.util.eventbus.Subscribe;
import com.sk89q.worldedit.world.World;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/**
 * Log what each WorldEdit edit changed, as a {@code worldedit_edit} line: the blocks placed and removed by type, the
 * bounds, and every block with tile-entity data, such as a pasted machine. The logged command has only the text, and
 * nothing of what {@code //paste}, {@code //stack}, schematics, brushes and wand tools changed.
 *
 * <p>
 * WorldEdit posts an EditSessionEvent once per stage while WorldEdit builds an EditSession. At BEFORE_CHANGE, the
 * layer closest to the world, the session's extent is wrapped in a {@link Recorder}. The recorder therefore gets
 * each block write after masks, history and reordering, and records a write only when the world changed. Every
 * EditSession is wrapped, so the recorder gets the writes of commands, brushes, CraftScripts and {@code //undo}.
 * Edits run on the server thread inside one tick, so the class logs one line per session per tick, at the tick's
 * end. The command line logged in the same tick has the text.
 *
 * <p>
 * Construct the class only when WorldEdit is loaded. GTNH doesn't include WorldEdit, and servers add WorldEdit
 * themselves.
 */
public final class WorldEditAudit {

    private static final int MAX_TILE_ENTITIES_PER_LINE = 256;

    private final AuditLogger logger;
    private final List<Recorder> recordersToFlush = new ArrayList<>();

    public WorldEditAudit(AuditLogger logger) {
        this.logger = logger;
    }

    public void register() {
        WorldEdit.getInstance()
            .getEventBus()
            .register(this);
    }

    public void unregister() {
        WorldEdit.getInstance()
            .getEventBus()
            .unregister(this);
    }

    @Subscribe
    public void onEditSession(EditSessionEvent event) {
        if (event.getStage() != EditSession.Stage.BEFORE_CHANGE) {
            return;
        }
        event.setExtent(new Recorder(event.getExtent(), event.getActor(), readDimension(event.getWorld())));
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        final List<JsonObject> lines = new ArrayList<>();
        synchronized (recordersToFlush) {
            if (recordersToFlush.isEmpty()) {
                return;
            }
            for (Recorder recorder : recordersToFlush) {
                lines.add(recorder.flush());
            }
            recordersToFlush.clear();
        }
        for (JsonObject line : lines) {
            logger.log("worldedit_edit", line);
        }
    }

    private static Integer readDimension(World world) {
        if (world instanceof ForgeWorld forge) {
            try {
                return forge.getWorld().provider.dimensionId;
            } catch (RuntimeException e) { // noqa: NAR142, degrade and report over the WorldEdit world reference
                return null; // the world unloaded under the session
            }
        }
        return null;
    }

    /** One EditSession's tally since its last flush. */
    private final class Recorder extends AbstractDelegateExtent {

        private final String actorName;
        private final UUID actorUuid;
        private final Integer dimension;

        private final Map<Integer, long[]> placed = new HashMap<>();
        private final Map<Integer, long[]> removed = new HashMap<>();
        private JsonArray tileEntities = new JsonArray();
        private long changed;
        private int tileEntitiesTotal;
        private int minX, minY, minZ, maxX, maxY, maxZ;
        private boolean queued;

        private Recorder(Extent extent, Actor actor, Integer dimension) {
            super(extent);
            this.actorName = actor == null ? null : actor.getName();
            this.actorUuid = actor == null ? null : actor.getUniqueId();
            this.dimension = dimension;
        }

        @Override
        public boolean setBlock(Vector location, BaseBlock block) throws WorldEditException {
            final BaseBlock before = getExtent().getLazyBlock(location);
            final boolean didChange = super.setBlock(location, block);
            if (didChange) {
                record(location, before, block);
            }
            return didChange;
        }

        /**
         * Add one changed block to the tally, and queue this recorder for the next flush. The tally changes only inside
         * {@code synchronized (recordersToFlush)}.
         */
        private void record(Vector at, BaseBlock before, BaseBlock after) {
            final int x = at.getBlockX(), y = at.getBlockY(), z = at.getBlockZ();
            synchronized (recordersToFlush) {
                if (changed == 0) {
                    minX = maxX = x;
                    minY = maxY = y;
                    minZ = maxZ = z;
                } else {
                    minX = Math.min(minX, x);
                    minY = Math.min(minY, y);
                    minZ = Math.min(minZ, z);
                    maxX = Math.max(maxX, x);
                    maxY = Math.max(maxY, y);
                    maxZ = Math.max(maxZ, z);
                }
                changed++;
                placed.computeIfAbsent(packBlockKey(after.getId(), after.getData()), k -> new long[1])[0]++;
                removed.computeIfAbsent(packBlockKey(before.getId(), before.getData()), k -> new long[1])[0]++;
                if (after.hasNbtData()) {
                    tileEntitiesTotal++;
                    if (tileEntities.size() < MAX_TILE_ENTITIES_PER_LINE) {
                        tileEntities.add(describeTileEntity(at, after));
                    }
                }
                if (!queued) {
                    queued = true;
                    recordersToFlush.add(this);
                }
            }
        }

        // block id and meta in one int, so counting a write is a map increment with no string built
        private static int packBlockKey(int id, int data) {
            return (id << 16) | (data & 0xFFFF);
        }

        private static JsonArray describeTileEntity(Vector at, BaseBlock block) {
            final JsonArray entry = new JsonArray();
            entry.add(new JsonPrimitive(at.getBlockX()));
            entry.add(new JsonPrimitive(at.getBlockY()));
            entry.add(new JsonPrimitive(at.getBlockZ()));
            entry.add(new JsonPrimitive(readBlockName(packBlockKey(block.getId(), block.getData()))));
            entry.add(new JsonPrimitive(String.valueOf(block.getNbtId())));
            // every GT machine has the same tile entity id. The mID tag has the machine's meta tile ID
            final CompoundTag tag = block.getNbtData();
            final Tag mId = tag == null ? null
                : tag.getValue()
                    .get("mID");
            if (mId != null && mId.getValue() instanceof Number n) {
                entry.add(new JsonPrimitive(n.intValue()));
            }
            return entry;
        }

        private static String readBlockName(int blockKey) {
            final Block block = Block.getBlockById(blockKey >>> 16);
            return Block.blockRegistry.getNameForObject(block) + "@" + (blockKey & 0xFFFF);
        }

        /**
         * Build the line for every change since the last flush, and reset the tally. Call flush() only inside
         * {@code synchronized (recordersToFlush)}.
         */
        private JsonObject flush() {
            final JsonObject data = new JsonObject();
            if (actorUuid != null) {
                data.addProperty("uuid", actorUuid.toString());
            }
            if (actorName != null) {
                data.addProperty("name", actorName);
            }
            if (dimension != null) {
                data.addProperty("dim", dimension);
            }
            data.addProperty("changed", changed);
            data.add("placed", countsByBlock(placed));
            data.add("removed", countsByBlock(removed));
            data.add("min", buildPositionArray(minX, minY, minZ));
            data.add("max", buildPositionArray(maxX, maxY, maxZ));
            data.addProperty("tileEntitiesTotal", tileEntitiesTotal);
            if (tileEntitiesTotal > 0) {
                data.add("tileEntities", tileEntities);
            }
            placed.clear();
            removed.clear();
            changed = 0;
            // the logged line has this array, and the writer thread serializes the line later. Replace the array.
            // Clearing the array would empty the tileEntities of the logged line
            tileEntities = new JsonArray();
            tileEntitiesTotal = 0;
            queued = false;
            return data;
        }

        // largest count first
        private static JsonObject countsByBlock(Map<Integer, long[]> tally) {
            final JsonObject out = new JsonObject();
            tally.entrySet()
                .stream()
                .sorted((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0]))
                .forEach(e -> out.addProperty(readBlockName(e.getKey()), e.getValue()[0]));
            return out;
        }

        private static JsonArray buildPositionArray(int x, int y, int z) {
            final JsonArray a = new JsonArray();
            a.add(new JsonPrimitive(x));
            a.add(new JsonPrimitive(y));
            a.add(new JsonPrimitive(z));
            return a;
        }
    }
}
