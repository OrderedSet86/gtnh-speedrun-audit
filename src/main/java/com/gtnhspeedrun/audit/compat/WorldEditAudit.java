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

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/**
 * What every WorldEdit edit changed, as worldedit_edit: blocks placed and removed by type, the bounds, and every
 * block that came with tile-entity data (a pasted machine). The command line only has the text, and
 * {@code //paste}, {@code //stack}, schematics, brushes and wand tools say nothing about contents there.
 *
 * <p>
 * Every EditSession posts EditSessionEvent once per stage while it is built. At BEFORE_CHANGE, the layer closest
 * to the world, the session's extent is wrapped in a {@link Recorder}, so each block write is seen after masks,
 * history and reordering and only when the world actually changed. That covers commands, brushes, CraftScripts
 * and {@code //undo} alike. Edits run on the server thread inside one tick, so one line per session per tick is
 * logged at the tick's end; the command line from the same tick carries the text.
 *
 * <p>
 * Only constructed when WorldEdit is loaded. The pack does not ship it; servers add it.
 */
public final class WorldEditAudit {

    /** Tile-entity entries kept per line; tileEntitiesTotal is always the exact count. */
    static final int TILE_ENTITY_CAP = 256;

    private final AuditLogger logger;
    /** Recorders that changed something since the last flush. */
    private final List<Recorder> pending = new ArrayList<>();

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
        event.setExtent(new Recorder(event.getExtent(), event.getActor(), dimension(event.getWorld())));
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        final List<JsonObject> lines = new ArrayList<>();
        synchronized (pending) {
            if (pending.isEmpty()) {
                return;
            }
            for (Recorder recorder : pending) {
                lines.add(recorder.flush());
            }
            pending.clear();
        }
        for (JsonObject line : lines) {
            logger.log("worldedit_edit", line);
        }
    }

    private static Integer dimension(com.sk89q.worldedit.world.World world) {
        if (world instanceof ForgeWorld forge) {
            try {
                return forge.getWorld().provider.dimensionId;
            } catch (RuntimeException e) {
                return null; // the world unloaded under the session
            }
        }
        return null;
    }

    /** Block id and meta packed into one int, so counting a write is a map increment with no string built. */
    private static int pack(int id, int data) {
        return (id << 16) | (data & 0xFFFF);
    }

    private static String name(int packed) {
        final Block block = Block.getBlockById(packed >>> 16);
        return Block.blockRegistry.getNameForObject(block) + "@" + (packed & 0xFFFF);
    }

    /** One EditSession's tally since its last flush. */
    final class Recorder extends AbstractDelegateExtent {

        private final String actorName;
        private final UUID actorUuid;
        private final Integer dim;

        private final Map<Integer, long[]> placed = new HashMap<>();
        private final Map<Integer, long[]> removed = new HashMap<>();
        private JsonArray tileEntities = new JsonArray();
        private long changed;
        private int tileEntitiesTotal;
        private int minX, minY, minZ, maxX, maxY, maxZ;
        private boolean queued;

        Recorder(Extent extent, Actor actor, Integer dim) {
            super(extent);
            this.actorName = actor == null ? null : actor.getName();
            this.actorUuid = actor == null ? null : actor.getUniqueId();
            this.dim = dim;
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

        private void record(Vector at, BaseBlock before, BaseBlock after) {
            final int x = at.getBlockX(), y = at.getBlockY(), z = at.getBlockZ();
            synchronized (pending) {
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
                placed.computeIfAbsent(pack(after.getId(), after.getData()), k -> new long[1])[0]++;
                removed.computeIfAbsent(pack(before.getId(), before.getData()), k -> new long[1])[0]++;
                if (after.hasNbtData()) {
                    tileEntitiesTotal++;
                    if (tileEntities.size() < TILE_ENTITY_CAP) {
                        tileEntities.add(tileEntity(x, y, z, after));
                    }
                }
                if (!queued) {
                    queued = true;
                    pending.add(this);
                }
            }
        }

        private JsonArray tileEntity(int x, int y, int z, BaseBlock block) {
            final JsonArray entry = new JsonArray();
            entry.add(new JsonPrimitive(x));
            entry.add(new JsonPrimitive(y));
            entry.add(new JsonPrimitive(z));
            entry.add(new JsonPrimitive(name(pack(block.getId(), block.getData()))));
            entry.add(new JsonPrimitive(String.valueOf(block.getNbtId())));
            // A GT machine's tile is the same for every machine; its meta tile ID says which one.
            final CompoundTag tag = block.getNbtData();
            final Tag mId = tag == null ? null
                : tag.getValue()
                    .get("mID");
            if (mId != null && mId.getValue() instanceof Number n) {
                entry.add(new JsonPrimitive(n.intValue()));
            }
            return entry;
        }

        /** The line for everything since the last flush, then a fresh tally. Called with {@code pending} held. */
        JsonObject flush() {
            final JsonObject data = new JsonObject();
            if (actorUuid != null) {
                data.addProperty("uuid", actorUuid.toString());
            }
            if (actorName != null) {
                data.addProperty("name", actorName);
            }
            if (dim != null) {
                data.addProperty("dim", dim);
            }
            data.addProperty("changed", changed);
            data.add("placed", counts(placed));
            data.add("removed", counts(removed));
            data.add("min", position(minX, minY, minZ));
            data.add("max", position(maxX, maxY, maxZ));
            data.addProperty("tileEntitiesTotal", tileEntitiesTotal);
            if (tileEntitiesTotal > 0) {
                data.add("tileEntities", tileEntities);
            }
            placed.clear();
            removed.clear();
            changed = 0;
            // a new array, not cleared: the logged line is serialized later, on the writer thread
            tileEntities = new JsonArray();
            tileEntitiesTotal = 0;
            queued = false;
            return data;
        }
    }

    private static JsonObject counts(Map<Integer, long[]> tally) {
        final JsonObject out = new JsonObject();
        tally.entrySet()
            .stream()
            .sorted((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0]))
            .forEach(e -> out.addProperty(name(e.getKey()), e.getValue()[0]));
        return out;
    }

    private static JsonArray position(int x, int y, int z) {
        final JsonArray a = new JsonArray();
        a.add(new JsonPrimitive(x));
        a.add(new JsonPrimitive(y));
        a.add(new JsonPrimitive(z));
        return a;
    }
}
