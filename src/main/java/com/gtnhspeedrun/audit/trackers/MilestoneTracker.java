package com.gtnhspeedrun.audit.trackers;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraftforge.event.entity.player.AchievementEvent;

import com.google.gson.JsonObject;
import com.gtnhspeedrun.audit.core.AuditLogger;
import com.gtnhspeedrun.audit.core.ChainAnchorData;
import com.gtnhspeedrun.audit.snapshot.ItemKey;
import com.gtnhspeedrun.audit.snapshot.KeyItemIndex;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;

/**
 * Log the milestones of the pace chart: dimension first visits, achievements, and key-item crafts.
 *
 * <p>
 * The Moon is dimension 28. First visits and achievements are deduplicated through the anchor. A rollback restores
 * the anchor with the world, so these milestones are logged again after a rollback.
 */
public final class MilestoneTracker {

    private final AuditLogger logger;
    private final ChainAnchorData anchor;
    private final KeyItemIndex keyItems;

    public MilestoneTracker(AuditLogger logger, ChainAnchorData anchor, KeyItemIndex keyItems) {
        this.logger = logger;
        this.anchor = anchor;
        this.keyItems = keyItems;
    }

    /**
     * Count the login dimension as visited. A first visit of the spawn dimension would otherwise come on the return
     * trip.
     */
    @SubscribeEvent
    public void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.player instanceof EntityPlayerMP player) {
            logFirstVisit(player, player.dimension, "login");
        }
    }

    /**
     * Log every dimension change, with the player's arrival position. The rules restrict teleports between
     * dimensions, so each change is logged, not only a first visit.
     *
     * <p>
     * A teleport command's line and the {@code dim_change} line that the command caused are a tick or two apart. A
     * portal or a rocket has no command line before the change.
     */
    @SubscribeEvent
    public void onDimensionChange(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.player instanceof EntityPlayerMP player) {
            final JsonObject data = new JsonObject();
            data.addProperty(
                "uuid",
                player.getGameProfile()
                    .getId()
                    .toString());
            data.addProperty("name", player.getCommandSenderName());
            data.addProperty("fromDim", event.fromDim);
            data.addProperty("toDim", event.toDim);
            data.addProperty("x", (int) Math.floor(player.posX));
            data.addProperty("y", (int) Math.floor(player.posY));
            data.addProperty("z", (int) Math.floor(player.posZ));
            logger.log("dim_change", data);
            logFirstVisit(player, event.toDim, "travel");
        }
    }

    private void logFirstVisit(EntityPlayerMP player, int dim, String cause) {
        if (!anchor.visitedDims.add(String.valueOf(dim))) {
            return;
        }
        anchor.markDirty();
        final JsonObject data = new JsonObject();
        data.addProperty(
            "uuid",
            player.getGameProfile()
                .getId()
                .toString());
        data.addProperty("name", player.getCommandSenderName());
        data.addProperty("dim", dim);
        data.addProperty("dimName", player.worldObj.provider.getDimensionName());
        data.addProperty("cause", cause);
        logger.log("dim_first_visit", data);
    }

    /**
     * Log a player's first trigger of each achievement. Forge posts {@code AchievementEvent} on every
     * {@code triggerAchievement} call, before vanilla checks that the parent achievement is unlocked. The event
     * therefore also fires for an achievement that the player has already or cannot unlock yet. The anchor's
     * {@code earnedAchievements} set dedupes the lines, and a trigger whose parent is locked is still logged. Vanilla
     * saves the unlocked state in the per-player stats file, and that file is not part of the audited world state.
     */
    @SubscribeEvent
    public void onAchievement(AchievementEvent event) {
        if (!(event.entityPlayer instanceof EntityPlayerMP player)) {
            return;
        }
        final String id = event.achievement.statId;
        if (!anchor.earnedAchievements.add(
            player.getGameProfile()
                .getId() + ":"
                + id)) {
            return;
        }
        anchor.markDirty();
        final JsonObject data = new JsonObject();
        data.addProperty(
            "uuid",
            player.getGameProfile()
                .getId()
                .toString());
        data.addProperty("name", player.getCommandSenderName());
        data.addProperty("achId", id);
        data.addProperty(
            "achName",
            event.achievement.func_150951_e()
                .getUnformattedText());
        logger.log("achievement", data);
    }

    /**
     * Check the crafted stack against the key-item watchlist, and log a {@code key_item_craft} line for every
     * craft of a key item that was seen before.
     */
    @SubscribeEvent
    public void onCraft(PlayerEvent.ItemCraftedEvent event) {
        if (!(event.player instanceof EntityPlayerMP player && event.crafting != null)) {
            return;
        }
        keyItems.checkStack(
            event.crafting,
            "craft",
            player.getGameProfile()
                .getId()
                .toString());
        // a craft after the first sighting is logged too, so verifiers have the craft counts
        final ItemStack stack = event.crafting;
        final String base = ItemKey.formatBaseKey(stack);
        final String noMeta = base.substring(0, base.indexOf('@'));
        if (anchor.seenKeyItems.contains(base) || anchor.seenKeyItems.contains(noMeta)) {
            final JsonObject data = new JsonObject();
            data.addProperty(
                "uuid",
                player.getGameProfile()
                    .getId()
                    .toString());
            data.addProperty("itemKey", base);
            data.addProperty("count", stack.stackSize);
            logger.log("key_item_craft", data);
        }
    }
}
