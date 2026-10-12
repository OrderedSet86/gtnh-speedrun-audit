package com.gtnhspeedrun.audit.trackers;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraftforge.event.world.BlockEvent;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.gtnhspeedrun.audit.core.AuditLogger;
import com.gtnhspeedrun.audit.core.JsonUtil;
import com.gtnhspeedrun.audit.snapshot.ItemKey;

import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/**
 * Every GT machine placement, for the run's machine-count stats, plus every placement of a configured
 * non-GT block (the Stargate structure by default) as block_placed. PlaceEvent fires once per deliberate
 * player placement — unlike MTE construction, which recurs on every chunk load. Pipes and cables share the
 * machine block, so they are split out by tile-entity class (string match keeps GT types off this class's
 * constant pool — it must load on GT-less setups too).
 *
 * <p>
 * Watched blocks ({@code watchedBlocks}, magic blocks by default) log both placement and player breaking as
 * watched_block, so a node, jar or altar appearing or disappearing has a who and when. Releasing a node from a
 * jar runs through the jar item's onItemUse, which Forge wraps in a captured PlaceEvent, so it is caught; jarring
 * a node with a wand sets the block directly and fires nothing — the node census shows that one as a node gone.
 *
 * <p>
 * A Matter Manipulator fires PlaceEvent as a permission check before it places anything, with the block already
 * at the spot, and then places through ItemBlock.placeBlockAt, which fires nothing. So its checks are kept for the
 * tick, and at the tick's end every checked spot that now holds a different GT machine is logged, one bulk_placed
 * line per player, dimension and manipulator. A checked spot that already held that machine is only counted.
 * Needs this tracker on both buses: Forge for PlaceEvent, FML for the end of the tick.
 */
public final class MachinePlacementTracker {

    public static final String GT_MACHINE_BLOCK = "gregtech:gt.blockmachines";
    public static final String MANIPULATOR_PREFIX = "matter-manipulator:";

    private final AuditLogger logger;
    private final Set<String> trackedBlocks;
    private final Set<String> watchedBlocks;
    private final String machineBlock;
    private final String manipulatorPrefix;
    /** This tick's manipulator checks by spot. The first check of a spot has the block before the placement. */
    private final Map<String, ManipulatorCheck> manipulatorChecks = new LinkedHashMap<>();

    public MachinePlacementTracker(AuditLogger logger, Set<String> trackedBlocks) {
        this(logger, trackedBlocks, java.util.Collections.emptySet());
    }

    /** {@code watchedBlocks}: {@code modid:name} (any meta) or {@code modid:name@meta}. */
    public MachinePlacementTracker(AuditLogger logger, Set<String> trackedBlocks, Set<String> watchedBlocks) {
        this(logger, trackedBlocks, watchedBlocks, GT_MACHINE_BLOCK, MANIPULATOR_PREFIX);
    }

    /** The self-test passes vanilla stand-ins for the GT machine block and the manipulator's item key prefix. */
    public MachinePlacementTracker(AuditLogger logger, Set<String> trackedBlocks, Set<String> watchedBlocks,
        String machineBlock, String manipulatorPrefix) {
        this.logger = logger;
        this.trackedBlocks = trackedBlocks;
        this.watchedBlocks = watchedBlocks;
        this.machineBlock = machineBlock;
        this.manipulatorPrefix = manipulatorPrefix;
    }

    /** LOWEST: a protection mod canceling the placement means nothing was placed — nothing to count. */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onPlace(BlockEvent.PlaceEvent event) {
        if (event.isCanceled() || event.world.isRemote || !(event.player instanceof EntityPlayerMP player)) {
            return;
        }
        final String blockName = String.valueOf(Block.blockRegistry.getNameForObject(event.placedBlock));
        if (event.itemInHand != null && ItemKey.base(event.itemInHand)
            .startsWith(manipulatorPrefix)) {
            // the check carries the block already there, so nothing here was placed
            noteManipulatorCheck(event, player, blockName);
            return;
        }
        if (machineBlock.equals(blockName)) {
            final JsonObject data = placement(event, player);
            data.addProperty("kind", classify(event.world.getTileEntity(event.x, event.y, event.z)));
            logger.log("machine_placed", data);
        } else if (trackedBlocks.contains(blockName)) {
            final JsonObject data = placement(event, player);
            data.addProperty("block", blockName);
            logger.log("block_placed", data);
        }
        if (watches(watchedBlocks, blockName, event.blockMetadata)) {
            final JsonObject data = placement(event, player);
            data.addProperty("action", "place");
            data.addProperty("block", blockName);
            data.addProperty("meta", event.blockMetadata);
            logger.log("watched_block", data);
        }
    }

    private void noteManipulatorCheck(BlockEvent.PlaceEvent event, EntityPlayerMP player, String blockName) {
        final String spot = event.world.provider.dimensionId + ":" + event.x + "," + event.y + "," + event.z;
        if (manipulatorChecks.containsKey(spot)) {
            return;
        }
        final int machineBefore = machineBlock.equals(blockName)
            ? event.placedBlock.getDamageValue(event.world, event.x, event.y, event.z)
            : -1;
        manipulatorChecks.put(spot, new ManipulatorCheck(event, player, machineBefore));
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || manipulatorChecks.isEmpty()) {
            return;
        }
        final Map<String, ManipulatorBatch> batches = new LinkedHashMap<>();
        for (ManipulatorCheck check : manipulatorChecks.values()) {
            final Block now = check.world.getBlock(check.x, check.y, check.z);
            if (!machineBlock.equals(String.valueOf(Block.blockRegistry.getNameForObject(now)))) {
                continue;
            }
            // GT's machine block returns the tile entity's meta tile ID
            final int machineNow = now.getDamageValue(check.world, check.x, check.y, check.z);
            final ManipulatorBatch batch = batches.computeIfAbsent(check.batchKey(), k -> new ManipulatorBatch(check));
            if (machineNow == check.machineBefore) {
                batch.checked++;
            } else {
                batch.add(check, now, machineNow);
            }
        }
        manipulatorChecks.clear();
        for (ManipulatorBatch batch : batches.values()) {
            logger.log("bulk_placed", batch.finish());
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onBreak(BlockEvent.BreakEvent event) {
        if (event.isCanceled() || event.world.isRemote
            || watchedBlocks.isEmpty()
            || !(event.getPlayer() instanceof EntityPlayerMP player)) {
            return;
        }
        final String blockName = String.valueOf(Block.blockRegistry.getNameForObject(event.block));
        if (!watches(watchedBlocks, blockName, event.blockMetadata)) {
            return;
        }
        final JsonObject data = new JsonObject();
        data.addProperty("action", "break");
        data.addProperty(
            "uuid",
            player.getGameProfile()
                .getId()
                .toString());
        data.addProperty("name", player.getCommandSenderName());
        data.addProperty("block", blockName);
        data.addProperty("meta", event.blockMetadata);
        data.addProperty("dim", event.world.provider.dimensionId);
        data.addProperty("x", event.x);
        data.addProperty("y", event.y);
        data.addProperty("z", event.z);
        final TileEntity te = event.world.getTileEntity(event.x, event.y, event.z);
        if (te != null) {
            // What the block held as it went: a node's aspects, a jar's node, an altar's essence.
            final net.minecraft.nbt.NBTTagCompound tag = new net.minecraft.nbt.NBTTagCompound();
            try {
                te.writeToNBT(tag);
                data.addProperty("tileNbtB64", JsonUtil.nbtToB64(tag));
            } catch (RuntimeException e) {
                data.addProperty("tileNbtError", String.valueOf(e));
            }
        }
        logger.log("watched_block", data);
    }

    static boolean watches(Set<String> watched, String blockName, int meta) {
        return !watched.isEmpty() && (watched.contains(blockName) || watched.contains(blockName + "@" + meta));
    }

    private static JsonObject placement(BlockEvent.PlaceEvent event, EntityPlayerMP player) {
        final JsonObject data = new JsonObject();
        data.addProperty(
            "uuid",
            player.getGameProfile()
                .getId()
                .toString());
        data.addProperty("name", player.getCommandSenderName());
        if (event.itemInHand != null) {
            data.addProperty("itemKey", ItemKey.base(event.itemInHand));
            data.addProperty("displayName", JsonUtil.stripFormatting(event.itemInHand.getDisplayName()));
        }
        data.addProperty("dim", event.world.provider.dimensionId);
        data.addProperty("x", event.x);
        data.addProperty("y", event.y);
        data.addProperty("z", event.z);
        return data;
    }

    /** One manipulator permission check: the spot, who checked it, and the GT machine there before (-1 if none). */
    private static final class ManipulatorCheck {

        final World world;
        final int x;
        final int y;
        final int z;
        final String uuid;
        final String name;
        final String itemKey;
        final String displayName;
        final int machineBefore;

        ManipulatorCheck(BlockEvent.PlaceEvent event, EntityPlayerMP player, int machineBefore) {
            this.world = event.world;
            this.x = event.x;
            this.y = event.y;
            this.z = event.z;
            this.uuid = player.getGameProfile()
                .getId()
                .toString();
            this.name = player.getCommandSenderName();
            this.itemKey = ItemKey.base(event.itemInHand);
            this.displayName = JsonUtil.stripFormatting(event.itemInHand.getDisplayName());
            this.machineBefore = machineBefore;
        }

        String batchKey() {
            return uuid + "|" + world.provider.dimensionId + "|" + itemKey;
        }
    }

    /**
     * One bulk_placed line: {@code machines} and {@code pipes} as [x, y, z, meta tile ID], {@code names} by meta
     * tile ID, and {@code checked}, the spots that already held the same machine.
     */
    private static final class ManipulatorBatch {

        final JsonObject data = new JsonObject();
        final JsonObject names = new JsonObject();
        final JsonArray machines = new JsonArray();
        final JsonArray pipes = new JsonArray();
        int checked;

        ManipulatorBatch(ManipulatorCheck check) {
            data.addProperty("uuid", check.uuid);
            data.addProperty("name", check.name);
            data.addProperty("itemKey", check.itemKey);
            data.addProperty("displayName", check.displayName);
            data.addProperty("dim", check.world.provider.dimensionId);
            data.add("names", names);
            data.add("machines", machines);
            data.add("pipes", pipes);
        }

        void add(ManipulatorCheck check, Block block, int machineId) {
            final JsonArray entry = new JsonArray();
            entry.add(new JsonPrimitive(check.x));
            entry.add(new JsonPrimitive(check.y));
            entry.add(new JsonPrimitive(check.z));
            entry.add(new JsonPrimitive(machineId));
            final boolean pipe = "pipe".equals(classify(check.world.getTileEntity(check.x, check.y, check.z)));
            (pipe ? pipes : machines).add(entry);
            final String id = String.valueOf(machineId);
            if (!names.has(id)) {
                final String name = machineName(block, machineId);
                if (name != null) {
                    names.addProperty(id, name);
                }
            }
        }

        JsonObject finish() {
            data.addProperty("checked", checked);
            return data;
        }
    }

    private static String machineName(Block block, int machineId) {
        final Item item = Item.getItemFromBlock(block);
        if (item == null) {
            return null;
        }
        try {
            return JsonUtil.stripFormatting(new ItemStack(item, 1, machineId).getDisplayName());
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String classify(TileEntity te) {
        if (te == null) {
            return "unknown";
        }
        final String cls = te.getClass()
            .getName();
        if (cls.contains("MetaPipeEntity") || cls.contains("BaseMetaPipeEntity")) {
            return "pipe";
        }
        return "machine";
    }
}
