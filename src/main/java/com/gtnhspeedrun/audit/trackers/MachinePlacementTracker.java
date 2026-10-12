package com.gtnhspeedrun.audit.trackers;

import java.util.Set;

import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.tileentity.TileEntity;
import net.minecraftforge.event.world.BlockEvent;

import com.google.gson.JsonObject;
import com.gtnhspeedrun.audit.core.AuditLogger;
import com.gtnhspeedrun.audit.core.JsonUtil;
import com.gtnhspeedrun.audit.snapshot.ItemKey;

import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;

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
 */
public final class MachinePlacementTracker {

    private static final String GT_MACHINE_BLOCK = "gregtech:gt.blockmachines";

    private final AuditLogger logger;
    private final Set<String> trackedBlocks;
    private final Set<String> watchedBlocks;

    public MachinePlacementTracker(AuditLogger logger, Set<String> trackedBlocks) {
        this(logger, trackedBlocks, java.util.Collections.emptySet());
    }

    /** {@code watchedBlocks}: {@code modid:name} (any meta) or {@code modid:name@meta}. */
    public MachinePlacementTracker(AuditLogger logger, Set<String> trackedBlocks, Set<String> watchedBlocks) {
        this.logger = logger;
        this.trackedBlocks = trackedBlocks;
        this.watchedBlocks = watchedBlocks;
    }

    /** LOWEST: a protection mod canceling the placement means nothing was placed — nothing to count. */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onPlace(BlockEvent.PlaceEvent event) {
        if (event.isCanceled() || event.world.isRemote || !(event.player instanceof EntityPlayerMP player)) {
            return;
        }
        final String blockName = String.valueOf(Block.blockRegistry.getNameForObject(event.placedBlock));
        if (GT_MACHINE_BLOCK.equals(blockName)) {
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
