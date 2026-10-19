package com.gtnhspeedrun.audit.trackers;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraftforge.common.util.BlockSnapshot;
import net.minecraftforge.event.world.BlockEvent;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.gtnhspeedrun.audit.AuditConfig;
import com.gtnhspeedrun.audit.core.AuditLogger;
import com.gtnhspeedrun.audit.core.JsonUtil;
import com.gtnhspeedrun.audit.snapshot.ItemKey;

import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/**
 * Log every GT machine placement for the run's machine-count stats, and every placement of a configured non-GT
 * block as {@code block_placed}. The default configured blocks are the blocks of the Stargate structure.
 *
 * <p>
 * PlaceEvent fires once per deliberate player placement. MTE construction recurs on every chunk load. Pipes and cables
 * share the machine block, so pipes and cables are split out by tile-entity class name. The class name is matched as a
 * string, so GT types stay out of this class's constant pool. The class must load with no GT.
 *
 * <p>
 * A placement or a player break of a watched block is logged as {@code watched_block}, so a node, jar or altar that
 * appears or disappears has a player and a time. The default {@code watchedBlocks} config has magic blocks. Forge
 * wraps the jar item's {@code onItemUse} in a captured PlaceEvent, so the release of a node from a jar is logged.
 * When a wand jars a node, Thaumcraft sets the block directly and fires no event. The next node census then no
 * longer has the node.
 *
 * <p>
 * The Matter Manipulator mod fires PlaceEvent as a permission check before placing anything, with the block that is
 * already at the spot. The mod then places through {@code ItemBlock.placeBlockAt}, and
 * {@code ItemBlock.placeBlockAt} fires no event. So the checks of a tick are kept in {@code manipulatorChecks}, and
 * at the end of the tick every checked spot that now has a different GT machine is logged. The log has one
 * {@code bulk_placed} line per player, dimension and manipulator. A checked spot that had the same machine before is
 * only counted. Register the tracker on both buses: Forge for PlaceEvent, and FML for the end of the tick.
 *
 * <p>
 * The {@code itemKey} of a {@code machine_placed} line is the player's main-hand item. That item is not the machine
 * when a tool did the placement, such as a Vajra that swaps in the offhand block or a StructureLib hologram
 * projector. {@code machineId} and {@code machine} are the GT machine as read back from the world.
 *
 * <p>
 * Every block that a player in creative mode places or breaks is also logged as {@code creative_block}. A creative
 * placement uses up no item, so the placement appears in neither inventory snapshots nor {@code creative_slot} lines.
 * For a MultiPlaceEvent, such as for a bed or a door, each block of the placement is logged. Manipulator checks are not
 * placements, and are not logged as creative blocks.
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

    /**
     * Read the tracked blocks from {@link AuditConfig#trackedPlacements} and the watched blocks from
     * {@link AuditConfig#watchedBlocks}, with blank entries skipped. A watched entry is {@code modid:name} for any
     * meta, or {@code modid:name@meta}.
     *
     * @param machineBlock      the GT machine block name. The self-test passes a vanilla stand-in.
     * @param manipulatorPrefix the item key prefix of the Matter Manipulator. The self-test passes a vanilla stand-in.
     */
    public MachinePlacementTracker(AuditLogger logger, String machineBlock, String manipulatorPrefix) {
        this.logger = logger;
        this.trackedBlocks = collectNonBlank(AuditConfig.trackedPlacements);
        this.watchedBlocks = collectNonBlank(AuditConfig.watchedBlocks);
        this.machineBlock = machineBlock;
        this.manipulatorPrefix = manipulatorPrefix;
    }

    private static Set<String> collectNonBlank(String[] entries) {
        final Set<String> trimmed = new HashSet<>();
        for (String entry : entries) {
            final String value = entry.trim();
            if (!value.isEmpty()) {
                trimmed.add(value);
            }
        }
        return trimmed;
    }

    /**
     * At LOWEST priority, the handler runs after any protection mod that cancels the placement. A canceled placement
     * leaves nothing placed, so a canceled event is skipped.
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onPlace(BlockEvent.PlaceEvent event) {
        if (event.isCanceled() || event.world.isRemote || !(event.player instanceof EntityPlayerMP player)) {
            return;
        }
        final String blockName = String.valueOf(Block.blockRegistry.getNameForObject(event.placedBlock));
        if (event.itemInHand != null && ItemKey.formatBaseKey(event.itemInHand)
            .startsWith(manipulatorPrefix)) {
            // the check's placedBlock is the block already at the spot, and nothing was placed
            noteManipulatorCheck(event, player, blockName);
            return;
        }
        // PlaceEvent.blockMetadata is the meta of the REPLACED block, because Forge builds the event from the
        // snapshot taken before the placement. The world has the new block already, so the new block's meta is read
        // from the world.
        final int placedMeta = event.world.getBlockMetadata(event.x, event.y, event.z);
        if (player.theItemInWorldManager.isCreative()) {
            logCreativePlacement(event, player, placedMeta);
        }
        if (machineBlock.equals(blockName)) {
            final JsonObject data = describePlacement(event, player);
            data.addProperty("kind", classify(event.world.getTileEntity(event.x, event.y, event.z)));
            final int machineId = event.placedBlock.getDamageValue(event.world, event.x, event.y, event.z);
            data.addProperty("machineId", machineId);
            final String machine = readMachineName(event.placedBlock, machineId);
            if (machine != null) {
                data.addProperty("machine", machine);
            }
            logger.log("machine_placed", data);
        } else if (trackedBlocks.contains(blockName)) {
            final JsonObject data = describePlacement(event, player);
            data.addProperty("block", blockName);
            logger.log("block_placed", data);
        }
        if (isWatched(watchedBlocks, blockName, placedMeta)) {
            final JsonObject data = describePlacement(event, player);
            data.addProperty("action", "place");
            data.addProperty("block", blockName);
            data.addProperty("meta", placedMeta);
            logger.log("watched_block", data);
        }
    }

    /**
     * Log one {@code bulk_placed} line per player, dimension and manipulator for this tick's manipulator checks, and
     * clear the checks.
     */
    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || manipulatorChecks.isEmpty()) {
            return;
        }
        final Map<String, ManipulatorBatch> batches = new LinkedHashMap<>();
        for (ManipulatorCheck check : manipulatorChecks.values()) {
            final Block now = check.world()
                .getBlock(check.x(), check.y(), check.z());
            if (!machineBlock.equals(String.valueOf(Block.blockRegistry.getNameForObject(now)))) {
                continue;
            }
            // getDamageValue() of GT's machine block returns the tile entity's meta tile ID
            final int machineNow = now.getDamageValue(check.world(), check.x(), check.y(), check.z());
            final ManipulatorBatch batch = batches
                .computeIfAbsent(check.batchKey(), batchKey -> new ManipulatorBatch(check));
            if (machineNow == check.machineBefore()) {
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

    /** Log a creative break as {@code creative_block}, and a break of a watched block with its tile's NBT. */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onBreak(BlockEvent.BreakEvent event) {
        if (event.isCanceled() || event.world.isRemote || !(event.getPlayer() instanceof EntityPlayerMP player)) {
            return;
        }
        if (player.theItemInWorldManager.isCreative()) {
            logCreative(
                player,
                new CreativeBlock(
                    "break",
                    event.world,
                    event.x,
                    event.y,
                    event.z,
                    event.block,
                    event.blockMetadata,
                    null));
        }
        if (watchedBlocks.isEmpty()) {
            return;
        }
        final String blockName = String.valueOf(Block.blockRegistry.getNameForObject(event.block));
        if (!isWatched(watchedBlocks, blockName, event.blockMetadata)) {
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
        final TileEntity tile = event.world.getTileEntity(event.x, event.y, event.z);
        if (tile != null) {
            // the NBT of the broken block's tile, such as a node's aspects, a jar's node or an altar's essence
            final NBTTagCompound tag = new NBTTagCompound();
            try {
                tile.writeToNBT(tag);
                data.addProperty("tileNbtB64", JsonUtil.nbtToB64(tag));
            } catch (RuntimeException e) { // noqa: NAR142, degrade and report over another mod's writeToNBT()
                data.addProperty("tileNbtError", String.valueOf(e));
            }
        }
        logger.log("watched_block", data);
    }

    private void noteManipulatorCheck(BlockEvent.PlaceEvent event, EntityPlayerMP player, String blockName) {
        final String spot = event.world.provider.dimensionId + ":" + event.x + "," + event.y + "," + event.z;
        if (manipulatorChecks.containsKey(spot)) {
            return;
        }
        final int machineBefore = machineBlock.equals(blockName)
            ? event.placedBlock.getDamageValue(event.world, event.x, event.y, event.z)
            : -1;
        manipulatorChecks.put(spot, ManipulatorCheck.capture(event, player, machineBefore));
    }

    /** Log each block of a creative placement as {@code creative_block}, including every block of a MultiPlaceEvent. */
    private void logCreativePlacement(BlockEvent.PlaceEvent event, EntityPlayerMP player, int placedMeta) {
        if (event instanceof BlockEvent.MultiPlaceEvent multi) {
            for (BlockSnapshot snapshot : multi.getReplacedBlockSnapshots()) {
                logCreative(
                    player,
                    new CreativeBlock(
                        "place",
                        event.world,
                        snapshot.x,
                        snapshot.y,
                        snapshot.z,
                        event.world.getBlock(snapshot.x, snapshot.y, snapshot.z),
                        event.world.getBlockMetadata(snapshot.x, snapshot.y, snapshot.z),
                        event.itemInHand));
            }
            return;
        }
        logCreative(
            player,
            new CreativeBlock(
                "place",
                event.world,
                event.x,
                event.y,
                event.z,
                event.placedBlock,
                placedMeta,
                event.itemInHand));
    }

    /**
     * Log a {@code creative_block} line. The line has {@code tile} true when the spot has a tile entity: after the
     * placement for a place, and before the break for a break, because BreakEvent fires before the block is removed.
     */
    private void logCreative(EntityPlayerMP player, CreativeBlock change) {
        final JsonObject data = new JsonObject();
        data.addProperty("action", change.action());
        data.addProperty(
            "uuid",
            player.getGameProfile()
                .getId()
                .toString());
        data.addProperty("name", player.getCommandSenderName());
        data.addProperty("block", String.valueOf(Block.blockRegistry.getNameForObject(change.block())));
        data.addProperty("meta", change.meta());
        if (change.itemInHand() != null) {
            data.addProperty("itemKey", ItemKey.formatBaseKey(change.itemInHand()));
        }
        data.addProperty("dim", change.world().provider.dimensionId);
        data.addProperty("x", change.x());
        data.addProperty("y", change.y());
        data.addProperty("z", change.z());
        if (change.world()
            .getTileEntity(change.x(), change.y(), change.z()) != null) {
            data.addProperty("tile", true);
        }
        logger.log("creative_block", data);
    }

    private static JsonObject describePlacement(BlockEvent.PlaceEvent event, EntityPlayerMP player) {
        final JsonObject data = new JsonObject();
        data.addProperty(
            "uuid",
            player.getGameProfile()
                .getId()
                .toString());
        data.addProperty("name", player.getCommandSenderName());
        if (event.itemInHand != null) {
            data.addProperty("itemKey", ItemKey.formatBaseKey(event.itemInHand));
            data.addProperty("displayName", JsonUtil.stripFormatting(event.itemInHand.getDisplayName()));
        }
        data.addProperty("dim", event.world.provider.dimensionId);
        data.addProperty("x", event.x);
        data.addProperty("y", event.y);
        data.addProperty("z", event.z);
        return data;
    }

    private static String classify(TileEntity tile) {
        if (tile == null) {
            return "unknown";
        }
        final String className = tile.getClass()
            .getName();
        if (className.contains("MetaPipeEntity") || className.contains("BaseMetaPipeEntity")) {
            return "pipe";
        }
        return "machine";
    }

    private static String readMachineName(Block block, int machineId) {
        final Item item = Item.getItemFromBlock(block);
        if (item == null) {
            return null;
        }
        try {
            return JsonUtil.stripFormatting(new ItemStack(item, 1, machineId).getDisplayName());
        } catch (RuntimeException e) { // noqa: NAR142, degrade and report over GT getDisplayName()
            return null;
        }
    }

    public static boolean isWatched(Set<String> watched, String blockName, int meta) {
        return !watched.isEmpty() && (watched.contains(blockName) || watched.contains(blockName + "@" + meta));
    }

    /** A creative placement or break of one block. {@code itemInHand} is null for a break. */
    private record CreativeBlock(String action, World world, int x, int y, int z, Block block, int meta,
        ItemStack itemInHand) {}

    /**
     * A manipulator permission check: the spot, the player, and the meta tile ID of the GT machine at the spot
     * before the placement, or -1 for none.
     */
    private record ManipulatorCheck(World world, int x, int y, int z, String uuid, String name, String itemKey,
        String displayName, int machineBefore) {

        private static ManipulatorCheck capture(BlockEvent.PlaceEvent event, EntityPlayerMP player, int machineBefore) {
            return new ManipulatorCheck(
                event.world,
                event.x,
                event.y,
                event.z,
                player.getGameProfile()
                    .getId()
                    .toString(),
                player.getCommandSenderName(),
                ItemKey.formatBaseKey(event.itemInHand),
                JsonUtil.stripFormatting(event.itemInHand.getDisplayName()),
                machineBefore);
        }

        private String batchKey() {
            return uuid + "|" + world.provider.dimensionId + "|" + itemKey;
        }
    }

    /**
     * One {@code bulk_placed} line: {@code machines} and {@code pipes} as [x, y, z, meta tile ID], {@code names} by
     * meta tile ID, and {@code checked}, the count of spots that had the same machine before.
     */
    private static final class ManipulatorBatch {

        private final JsonObject data = new JsonObject();
        private final JsonObject names = new JsonObject();
        private final JsonArray machines = new JsonArray();
        private final JsonArray pipes = new JsonArray();
        private int checked;

        private ManipulatorBatch(ManipulatorCheck check) {
            data.addProperty("uuid", check.uuid());
            data.addProperty("name", check.name());
            data.addProperty("itemKey", check.itemKey());
            data.addProperty("displayName", check.displayName());
            data.addProperty("dim", check.world().provider.dimensionId);
            data.add("names", names);
            data.add("machines", machines);
            data.add("pipes", pipes);
        }

        private void add(ManipulatorCheck check, Block block, int machineId) {
            final JsonArray entry = new JsonArray();
            entry.add(new JsonPrimitive(check.x()));
            entry.add(new JsonPrimitive(check.y()));
            entry.add(new JsonPrimitive(check.z()));
            entry.add(new JsonPrimitive(machineId));
            final boolean pipe = "pipe".equals(
                classify(
                    check.world()
                        .getTileEntity(check.x(), check.y(), check.z())));
            (pipe ? pipes : machines).add(entry);
            final String id = String.valueOf(machineId);
            if (!names.has(id)) {
                final String name = readMachineName(block, machineId);
                if (name != null) {
                    names.addProperty(id, name);
                }
            }
        }

        private JsonObject finish() {
            data.addProperty("checked", checked);
            return data;
        }
    }
}
