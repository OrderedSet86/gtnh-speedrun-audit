package com.gtnhspeedrun.audit.trackers;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.world.ChunkPosition;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.world.BlockEvent;
import net.minecraftforge.event.world.ExplosionEvent;

import org.jetbrains.annotations.Nullable;

import com.google.gson.JsonObject;
import com.gtnhspeedrun.audit.GtnhSpeedrunAudit;
import com.gtnhspeedrun.audit.core.AuditLogger;
import com.gtnhspeedrun.audit.core.JsonUtil;
import com.gtnhspeedrun.audit.snapshot.ItemKey;
import com.gtnhspeedrun.audit.trackers.MachinePlacementTracker.GtMachine;
import com.gtnhspeedrun.audit.trackers.MachinePlacementTracker.ManipulatorBatch;
import com.gtnhspeedrun.audit.trackers.MachinePlacementTracker.ManipulatorCheck;

import cpw.mods.fml.common.eventhandler.Event;
import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/**
 * Log every removal of a GT machine, hatch, pipe or cable block as {@code machine_removed}, with the cause, the way
 * {@link MachinePlacementTracker} logs placements. The Matter Manipulator removals of one player, dimension and
 * manipulator in one tick go in one {@code bulk_removed} line, the shape of {@code bulk_placed}.
 *
 * <p>
 * The GT breakBlock mixin calls {@link #logMachineRemoval} for each removal. The cause is the one noted earlier in
 * the same tick:
 * <ul>
 * <li>{@code broken} or {@code wrenched}: a player's BreakEvent, by the tool in the player's hand</li>
 * <li>{@code vajra}: a player's BreakEvent with a Vajra in hand, or a right-click on the block with a Vajra</li>
 * <li>{@code exploded}: an ExplosionEvent.Detonate over the spot, or the explosion of the GT machine</li>
 * <li>{@code burned}: a GT cable or pipe set on fire</li>
 * <li>{@code manipulator}: a Matter Manipulator check of the spot, or a BreakEvent with the manipulator in hand</li>
 * <li>{@code other}: no noted cause, such as a command or a WorldEdit edit</li>
 * </ul>
 * Register the instance on both buses: Forge for the cause events, and FML for the end of the tick.
 *
 * <pre>
 * {"cause":"other","class":"MTEBasicHullBronze","localName":"gt.blockmachines.hull.bronze.name","dim":0,"x":-26,
 *  "y":107,"z":-127,"kind":"machine","machineId":1,"machine":"Bronze Hull","machineKey":"gt.blockmachines.hull.bronze"}
 * {"uuid":"61def193-eb8f-31c9-926f-fbd6c10d1486","name":"SelfTestAlice","itemKey":"minecraft:stick@0",
 *  "displayName":"Stick","dim":0,"names":{"0":"Block of Iron"},"keys":{"0":"tile.blockIron"},
 *  "machines":[[19,104,-127,0]],"pipes":[]}
 * </pre>
 *
 * The first sample is the data of a {@code machine_removed} line from a pack server, and the second the data of a
 * self-test {@code bulk_removed} line.
 */
public final class MachineRemovals {

    /** The item classes of GT's Vajra and of Gravitation Suite's Vajra. */
    private static final Set<String> VAJRA_CLASSES = Set.of("gregtech.common.tools.ToolVajra", "gravisuite.ItemVajra");

    // set at server start and cleared at stop. The GT mixins have no instance to reach
    private static volatile @Nullable MachineRemovals active;

    private final AuditLogger logger;
    private final MachinePlacementTracker placements;
    private final Tools tools;
    private final PendingRemovalCauses causes = new PendingRemovalCauses();
    /** This tick's manipulator removals by player, dimension and manipulator. */
    private final Map<String, ManipulatorBatch> batches = new LinkedHashMap<>();

    /** @param placements has the machine block, the manipulator item and this tick's manipulator checks */
    public MachineRemovals(AuditLogger logger, MachinePlacementTracker placements, Tools tools) {
        this.logger = logger;
        this.placements = placements;
        this.tools = tools;
    }

    public static boolean isVajra(ItemStack stack) {
        return VAJRA_CLASSES.contains(
            stack.getItem()
                .getClass()
                .getName());
    }

    public static void activate(@Nullable MachineRemovals removals) {
        active = removals;
    }

    /**
     * Log the removal of the GT block at {@code site}. Call on the server thread at the start of the block's
     * breakBlock(), while the GT tile is still in the world.
     *
     * @param machine null when the spot has no GT tile
     */
    public static void logMachineRemoval(MachineSite site, @Nullable GtMachine machine) {
        final MachineRemovals removals = active;
        if (!(removals != null && !GtnhSpeedrunAudit.isOnClientThread())) {
            return;
        }
        removals.logRemoval(site, machine);
    }

    /** Note the cause of a removal that comes later in this tick, for a cause that fires no Forge event. */
    public static void noteRemovalCause(BlockSpot spot, RemovalCause.Cause cause) {
        final MachineRemovals removals = active;
        if (!(removals != null && !GtnhSpeedrunAudit.isOnClientThread())) {
            return;
        }
        removals.causes.note(spot, RemovalCause.ofWorld(cause));
    }

    /**
     * At LOWEST priority, the handler runs after any protection mod and after GT's wrench handler. Both can cancel the
     * break.
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onBreak(BlockEvent.BreakEvent event) {
        if (!(!event.isCanceled() && !event.world.isRemote
            && event.getPlayer() instanceof EntityPlayerMP player
            && placements.isMachineBlock(event.block))) {
            return;
        }
        final ItemStack inHand = player.getCurrentEquippedItem();
        final RemovalCause.Cause cause;
        if (placements.isManipulator(inHand)) {
            cause = RemovalCause.Cause.MANIPULATOR;
        } else if (inHand != null && tools.isVajra()
            .test(inHand)) {
                cause = RemovalCause.Cause.VAJRA;
            } else if (inHand != null && tools.isWrench()
                .test(inHand)) {
                    cause = RemovalCause.Cause.WRENCHED;
                } else {
                    cause = RemovalCause.Cause.BROKEN;
                }
        causes.note(
            new BlockSpot(event.world.provider.dimensionId, event.x, event.y, event.z),
            new RemovalCause(cause, describeRemover(player, inHand)));
    }

    /**
     * Note {@code vajra} for a right-click on a block with a Vajra in hand. Forge posts the event just before the
     * Vajra's onItemUse(), and onItemUse() removes the block with no BreakEvent. A right-click that removes nothing
     * leaves a note that the end of the tick clears.
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onRightClick(PlayerInteractEvent event) {
        if (!(event.action == PlayerInteractEvent.Action.RIGHT_CLICK_BLOCK && !event.isCanceled()
            && event.useItem != Event.Result.DENY
            && !event.world.isRemote
            && event.entityPlayer instanceof EntityPlayerMP player)) {
            return;
        }
        final ItemStack inHand = player.getCurrentEquippedItem();
        if (!(inHand != null && tools.isVajra()
            .test(inHand))) {
            return;
        }
        causes.note(
            new BlockSpot(event.world.provider.dimensionId, event.x, event.y, event.z),
            new RemovalCause(RemovalCause.Cause.VAJRA, describeRemover(player, inHand)));
    }

    /** Note {@code exploded} for every machine block that the explosion is about to remove. */
    @SubscribeEvent
    public void onDetonate(ExplosionEvent.Detonate event) {
        if (event.world.isRemote) {
            return;
        }
        for (ChunkPosition position : event.getAffectedBlocks()) {
            if (placements
                .isMachineBlock(event.world.getBlock(position.chunkPosX, position.chunkPosY, position.chunkPosZ))) {
                causes.note(
                    new BlockSpot(
                        event.world.provider.dimensionId,
                        position.chunkPosX,
                        position.chunkPosY,
                        position.chunkPosZ),
                    RemovalCause.ofWorld(RemovalCause.Cause.EXPLODED));
            }
        }
    }

    /** Log one {@code bulk_removed} line per manipulator batch of this tick, then forget this tick's causes. */
    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        for (ManipulatorBatch batch : batches.values()) {
            logger.log("bulk_removed", batch.finishRemoved());
        }
        batches.clear();
        causes.clear();
    }

    private static RemovalCause.Remover describeRemover(EntityPlayerMP player, @Nullable ItemStack inHand) {
        return new RemovalCause.Remover(
            player.getGameProfile()
                .getId()
                .toString(),
            player.getCommandSenderName(),
            inHand == null ? null : ItemKey.formatBaseKey(inHand),
            inHand == null ? null : JsonUtil.stripFormatting(inHand.getDisplayName()));
    }

    private void logRemoval(MachineSite site, @Nullable GtMachine machine) {
        final BlockSpot spot = new BlockSpot(site.dim(), site.x(), site.y(), site.z());
        final RemovalCause cause = findCause(spot);
        final RemovalCause.Remover remover = cause.remover();
        if (machine != null && remover != null && cause.cause().batched) {
            batches
                .computeIfAbsent(
                    remover.uuid() + "|" + site.dim() + "|" + remover.toolKey(),
                    batchKey -> new ManipulatorBatch(describeBatch(remover, site.dim())))
                .add(spot, machine);
            return;
        }
        final JsonObject data = new JsonObject();
        cause.writeTo(data);
        site.writeTo(data);
        data.addProperty("kind", machine == null ? "unknown" : machine.pipe() ? "pipe" : "machine");
        if (machine != null) {
            machine.writeTo(data);
        }
        logger.log("machine_removed", data);
    }

    /**
     * Return the noted cause of {@code spot}, else a manipulator cause when the Matter Manipulator checked the spot in
     * this tick, else {@link RemovalCause#OTHER}.
     */
    private RemovalCause findCause(BlockSpot spot) {
        final RemovalCause noted = causes.takeCause(spot);
        if (noted != null) {
            return noted;
        }
        // the manipulator replaces or clears a spot after a PlaceEvent check of the spot, and fires no BreakEvent
        final ManipulatorCheck check = placements.findManipulatorCheck(spot);
        if (check == null) {
            return RemovalCause.OTHER;
        }
        return new RemovalCause(
            RemovalCause.Cause.MANIPULATOR,
            new RemovalCause.Remover(check.uuid(), check.name(), check.itemKey(), check.displayName()));
    }

    private static JsonObject describeBatch(RemovalCause.Remover remover, int dim) {
        final JsonObject header = new JsonObject();
        header.addProperty("uuid", remover.uuid());
        header.addProperty("name", remover.playerName());
        header.addProperty("itemKey", remover.toolKey());
        header.addProperty("displayName", remover.toolName());
        header.addProperty("dim", dim);
        return header;
    }

    /**
     * Tell the tools in a player's hand apart.
     *
     * @param isWrench true for a wrench
     * @param isVajra  true for a Vajra
     */
    public record Tools(Predicate<ItemStack> isWrench, Predicate<ItemStack> isVajra) {}
}
