package com.gtnhspeedrun.audit.trackers;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;

import org.jetbrains.annotations.Nullable;

import com.google.gson.JsonObject;
import com.gtnhspeedrun.audit.GtnhSpeedrunAudit;
import com.gtnhspeedrun.audit.core.SessionManager;
import com.gtnhspeedrun.audit.snapshot.PlayerPlace;
import com.gtnhspeedrun.audit.snapshot.StackJson;

/**
 * Log the AE2 pattern events that the AE2 and GT mixins report: a pattern encoded in a pattern terminal, and a pattern
 * put into or taken out of a pattern provider's slot.
 *
 * <p>
 * A provider's slot-change callback gets no player. The line gets the player whose GUI click or Interface Terminal
 * action is being handled when the slot changes. Any other change has no player. The signatures have only vanilla, JDK
 * and audit types, so this class loads on a pack without AE2. Every sink runs on the server thread.
 */
public final class PatternSinks {

    // static: the mixins have no instance. Clicks and terminal actions are handled on the server thread
    private static EntityPlayer actingPlayer;

    private PatternSinks() {}

    /** Call at the head of a GUI click or an Interface Terminal action, with {@link #endPlayerAction} at the return. */
    public static void beginPlayerAction(EntityPlayer player) {
        actingPlayer = player;
    }

    public static void endPlayerAction() {
        actingPlayer = null;
    }

    /** Log a {@code pattern_encoded} line: the new pattern stack, with the stack's {@code author} tag. */
    public static void logPatternEncoded(EntityPlayerMP player, ItemStack pattern, String terminal) {
        final SessionManager session = GtnhSpeedrunAudit.session();
        if (session == null) {
            return;
        }
        final JsonObject data = describePlayer(player);
        PlayerPlace.addPlace(data, player);
        data.addProperty("terminal", terminal);
        data.addProperty("author", readAuthor(pattern));
        data.add("stack", StackJson.toJson(pattern));
        session.logger()
            .log("pattern_encoded", data);
    }

    /**
     * Log a {@code pattern_slot} line for a provider's slot change. The hook in GT's Crafting Input Buffer gets no
     * stack taken out, so the buffer's line has no {@code removed}, and a buffer line with neither stack is an emptied
     * slot.
     */
    public static void logPatternSlot(ProviderSlot place, @Nullable ItemStack removed, @Nullable ItemStack added) {
        final SessionManager session = GtnhSpeedrunAudit.session();
        if (session == null) {
            return;
        }
        final JsonObject data = actingPlayer == null ? new JsonObject() : describePlayer(actingPlayer);
        data.addProperty("provider", place.provider());
        data.addProperty("dim", place.dim());
        data.addProperty("x", place.x());
        data.addProperty("y", place.y());
        data.addProperty("z", place.z());
        data.addProperty("slot", place.slot());
        if (removed != null) {
            data.add("removed", StackJson.toJson(removed));
        }
        if (added != null) {
            data.addProperty("author", readAuthor(added));
            data.add("added", StackJson.toJson(added));
        }
        session.logger()
            .log("pattern_slot", data);
    }

    private static JsonObject describePlayer(EntityPlayer player) {
        final JsonObject data = new JsonObject();
        data.addProperty(
            "uuid",
            player.getUniqueID()
                .toString());
        data.addProperty("name", player.getCommandSenderName());
        return data;
    }

    private static @Nullable String readAuthor(ItemStack pattern) {
        return pattern.hasTagCompound() && pattern.getTagCompound()
            .hasKey("author") ? pattern.getTagCompound()
                .getString("author") : null;
    }

    /** A pattern provider's slot: the provider's class name, the provider's block, and the slot number. */
    public record ProviderSlot(String provider, int dim, int x, int y, int z, int slot) {}
}
