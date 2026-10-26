package com.gtnhspeedrun.audit.trackers;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;

import org.jetbrains.annotations.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.gtnhspeedrun.audit.GtnhSpeedrunAudit;
import com.gtnhspeedrun.audit.core.AttemptLimiter;
import com.gtnhspeedrun.audit.core.SessionManager;
import com.gtnhspeedrun.audit.snapshot.ItemKey;
import com.gtnhspeedrun.audit.snapshot.StackJson;

/**
 * Log the trash-can inserts, creative-inventory packets and /inv view clicks that the mixins report.
 *
 * <p>
 * Signatures use only vanilla, JDK and audit types. A mixin computes any modded-API values before the call, so this
 * class loads on a pack without Extra Utilities or ServerUtilities. Every log method returns early when no session is
 * logging.
 */
public final class InventoryChanges {

    private InventoryChanges() {}

    /**
     * Log a stack that a player put into an Extra Utilities trash can's GUI slot. The trash can deletes the stack at
     * once, except a node-upgrade filter in slot 0.
     */
    public static void logTrashInsert(EntityPlayerMP player, TileEntity can, ItemStack stack) {
        final SessionManager session = GtnhSpeedrunAudit.session();
        if (session == null) {
            return;
        }
        final JsonObject data = new JsonObject();
        if (player != null) {
            data.addProperty(
                "uuid",
                player.getGameProfile()
                    .getId()
                    .toString());
            data.addProperty("name", player.getCommandSenderName());
        }
        if (can != null && can.getWorldObj() != null) {
            data.addProperty("dim", can.getWorldObj().provider.dimensionId);
            data.addProperty("x", can.xCoord);
            data.addProperty("y", can.yCoord);
            data.addProperty("z", can.zCoord);
        }
        data.add("stack", StackJson.toJson(stack));
        session.logger()
            .log("trash_insert", data);
    }

    /**
     * Log a creative-inventory packet: a slot of the player's inventory set to a stack, or a stack dropped with a slot
     * below 0. A packet that leaves the slot unchanged is not logged.
     *
     * <p>
     * A take from the creative tabs and a move between slots both arrive as slot sets, so the line has the slot's
     * previous contents too. A verifier nets {@code previousKey} against {@code itemKey} across a player's lines to
     * tell a spawn from a move. The packet arrives outside creative only from a modified client, and vanilla's handler
     * then skips the packet. Such a packet is logged with {@code inCreative} false. Past the limit of
     * {@link AttemptLimiter}, the packet is counted, not logged.
     */
    public static void logCreativeSlot(EntityPlayerMP player, SlotChange change, boolean inCreative) {
        final ItemStack stack = change.stack();
        final ItemStack previous = change.previous();
        final SessionManager session = GtnhSpeedrunAudit.session();
        if (session == null || ItemStack.areItemStacksEqual(stack, previous)) {
            return;
        }
        final String uuid = player.getGameProfile()
            .getId()
            .toString();
        if (!(inCreative || session.attempts()
            .tryLog(uuid, player.getCommandSenderName(), "creative_slot"))) {
            return;
        }
        final JsonObject data = new JsonObject();
        data.addProperty("uuid", uuid);
        data.addProperty("name", player.getCommandSenderName());
        data.addProperty("slot", change.slot());
        if (stack != null && stack.getItem() != null) {
            data.addProperty("itemKey", ItemKey.formatBaseKey(stack));
            data.addProperty("count", stack.stackSize);
        }
        if (previous != null && previous.getItem() != null) {
            data.addProperty("previousKey", ItemKey.formatBaseKey(previous));
            data.addProperty("previousCount", previous.stackSize);
        }
        data.addProperty("inCreative", inCreative);
        data.addProperty("dim", player.dimension);
        session.logger()
            .log("creative_slot", data);
    }

    /**
     * The slot of a creative-inventory packet, with the stack that the packet sets and the stack the slot had.
     *
     * @param stack    null for an empty stack
     * @param previous null for an empty slot
     */
    public record SlotChange(int slot, @Nullable ItemStack stack, @Nullable ItemStack previous) {}

    /** Build an {@code invsee_click} line: an /inv view click that changed a slot. */
    public static final class InvseeClick {

        private final String viewId;
        private final EntityPlayer viewer;
        private String targetName;
        private String targetUuid;
        private boolean targetOnline;
        private int slotId;
        private int button;
        private int mode;
        private JsonArray changes;
        private JsonObject cursorBefore;
        private JsonObject cursorAfter;

        private InvseeClick(String viewId, EntityPlayer viewer) {
            this.viewId = viewId;
            this.viewer = viewer;
        }

        public static InvseeClick of(String viewId, EntityPlayer viewer) {
            return new InvseeClick(viewId, viewer);
        }

        public InvseeClick target(@Nullable String name, @Nullable String uuid, boolean online) {
            targetName = name;
            targetUuid = uuid;
            targetOnline = online;
            return this;
        }

        /** Set the arguments of {@code slotClick}. */
        public InvseeClick click(int clickedSlotId, int clickedButton, int clickMode) {
            slotId = clickedSlotId;
            button = clickedButton;
            mode = clickMode;
            return this;
        }

        /** @param changedSlots each changed slot, tagged viewer or target */
        public InvseeClick changes(JsonArray changedSlots) {
            changes = changedSlots;
            return this;
        }

        /** Set the cursor stack before and after the click. A null stack is left off the line. */
        public InvseeClick cursor(@Nullable JsonObject before, @Nullable JsonObject after) {
            cursorBefore = before;
            cursorAfter = after;
            return this;
        }

        /** Write the line when a session is logging. The target's name and uuid are written as null when unset. */
        public void log() {
            final SessionManager session = GtnhSpeedrunAudit.session();
            if (session == null) {
                return;
            }
            final JsonObject data = new JsonObject();
            data.addProperty(
                "uuid",
                viewer.getGameProfile()
                    .getId()
                    .toString());
            data.addProperty("name", viewer.getCommandSenderName());
            data.addProperty("targetName", targetName);
            data.addProperty("targetUuid", targetUuid);
            data.addProperty("viewId", viewId);
            data.addProperty("targetOnline", targetOnline);
            data.addProperty("slotId", slotId);
            data.addProperty("button", button);
            data.addProperty("mode", mode);
            data.add("changes", changes);
            if (cursorBefore != null) {
                data.add("cursorBefore", cursorBefore);
            }
            if (cursorAfter != null) {
                data.add("cursorAfter", cursorAfter);
            }
            session.logger()
                .log("invsee_click", data);
        }
    }
}
