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
 */
public final class MachinePlacementTracker {

    private static final String GT_MACHINE_BLOCK = "gregtech:gt.blockmachines";

    private final AuditLogger logger;
    private final Set<String> trackedBlocks;

    public MachinePlacementTracker(AuditLogger logger, Set<String> trackedBlocks) {
        this.logger = logger;
        this.trackedBlocks = trackedBlocks;
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
