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
 * The pace chart: dimension first-visits (moon% is dim 28), achievements and key-item crafts, all deduped
 * through the anchor so they rewind with the world on a rollback and legitimately re-earn.
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

    /** The spawn dimension counts too — without this, "Overworld" would first appear on the return trip. */
    @SubscribeEvent
    public void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.player instanceof EntityPlayerMP player) {
            firstVisit(player, player.dimension, "login");
        }
    }

    /**
     * Every dimension change, with where the player arrived. A teleport command's line and the dim_change it
     * caused share a tick or two; a portal or rocket has no command before it. Teleports between dimensions
     * matter to the rules, so this is logged every time, not only on a first visit.
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
            firstVisit(player, event.toDim, "travel");
        }
    }

    private void firstVisit(EntityPlayerMP player, int dim, String cause) {
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
     * Fires on every triggerAchievement call, earned or not — the anchor set is the dedupe. Vanilla's own
     * "already unlocked" state can't be the only gate: it lives in the per-player stats file, which is not
     * part of the audited world state.
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

    @SubscribeEvent
    public void onCraft(PlayerEvent.ItemCraftedEvent event) {
        if (!(event.player instanceof EntityPlayerMP player) || event.crafting == null) {
            return;
        }
        keyItems.checkStack(
            event.crafting,
            "craft",
            player.getGameProfile()
                .getId()
                .toString());
        // Also log crafts of watched items even after first-seen: cheap and gives verifiers count context.
        final ItemStack stack = event.crafting;
        final String base = ItemKey.base(stack);
        if (anchor.seenKeyItems.contains(base) || anchor.seenKeyItems.contains(base.substring(0, base.indexOf('@')))) {
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
