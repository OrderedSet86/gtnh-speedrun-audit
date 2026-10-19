package com.gtnhspeedrun.audit.trackers;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChatStyle;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.StatCollector;

import org.jetbrains.annotations.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.gtnhspeedrun.audit.GtnhSpeedrunAudit;
import com.gtnhspeedrun.audit.core.SessionManager;
import com.gtnhspeedrun.audit.snapshot.ItemKey;
import com.gtnhspeedrun.audit.snapshot.StackJson;

/**
 * Log the events of the mixins, through static entry points.
 *
 * <p>
 * Signatures use only vanilla, JDK and audit types. A mixin computes any modded-API values before the call, so this
 * class loads on a pack without some or all of the target mods. Every log method returns early when no session is
 * logging, and can run on the netty thread or the server thread. The logger is thread-safe, and the dedupe is
 * synchronized.
 */
public final class AuditSinks {

    // static: the mixins call these sinks with no instance. The cached reflection results stay valid for the
    // JVM's lifetime.
    private static volatile Map<Integer, String> neiPacketNames;
    private static volatile Method gtNameMethod; // static: GT has one name getter for the JVM's lifetime
    private static volatile boolean gtNameUnavailable; // static: set once when GT has neither name getter

    /**
     * The client handshake mod lists by connection. 1.7.10 FML logs the client's handshake mod list and then
     * discards the list, so the handshake mixin passes the list to {@link #storeClientModList}. The
     * {@code PlayerLoggedInEvent} handler reads the joining player's list. The keys are weak, so a dropped
     * connection's entry is removed once the connection is garbage-collected. The netty thread writes and the server
     * thread reads, so the map is synchronized.
     */
    private static final Map<Object, Map<String, String>> CLIENT_MODS = Collections
        .synchronizedMap(new WeakHashMap<>());

    private AuditSinks() {}

    /**
     * Return the MTE's local name key, or null when GT has no such method.
     *
     * <p>
     * GT renamed {@code MetaTileEntity.getLocalName()} (5.09.51.x and earlier, pack 2.8.4) to
     * {@code getLocalNameKey()} (5.09.54.x, dailies). The mixins run on both GT versions. A call compiled against
     * either name would throw NoSuchMethodError on the other version, so the method is looked up by reflection.
     */
    public static String gtLocalName(Object mte) {
        if (gtNameUnavailable) {
            return null;
        }
        Method method = gtNameMethod;
        if (method == null) {
            try {
                method = mte.getClass()
                    .getMethod("getLocalNameKey");
            } catch (NoSuchMethodException e) {
                try {
                    method = mte.getClass()
                        .getMethod("getLocalName");
                } catch (NoSuchMethodException oldNameMissing) {
                    gtNameUnavailable = true;
                    return null;
                }
            }
            gtNameMethod = method;
        }
        try {
            return String.valueOf(method.invoke(mte));
        } catch (ReflectiveOperationException | RuntimeException e) { // noqa: NAR142, reflection into GT code
            return null;
        }
    }

    // ------------------------------------------------------------------ NEI

    /**
     * Call at the head of {@code NEISPH.handlePacket}. The head runs before authentication, so a denied packet is
     * logged too.
     */
    public static void logNeiPacket(String uuid, String name, int type) {
        final SessionManager session = GtnhSpeedrunAudit.session();
        if (session == null) {
            return;
        }
        final JsonObject data = new JsonObject();
        data.addProperty("packetType", type);
        data.addProperty("packetName", readNeiPacketName(type));
        data.addProperty("uuid", uuid);
        data.addProperty("name", name);
        session.logger()
            .log("nei_packet", data);
    }

    /**
     * Return the name of a client-to-server packet type from NEI's {@code PacketIDs$C2S} constants, so the names stay
     * right after an NEI update renumbers the packets. The reflection runs once, on the first call. The caller is an
     * NEI mixin, so NEI is present.
     */
    private static String readNeiPacketName(int type) {
        Map<Integer, String> names = neiPacketNames;
        if (names == null) {
            names = new HashMap<>();
            try {
                final Class<?> c2s = Class.forName("codechicken.nei.PacketIDs$C2S");
                for (Field field : c2s.getFields()) {
                    if (Modifier.isStatic(field.getModifiers()) && field.getType() == int.class) {
                        names.put(field.getInt(null), field.getName());
                    }
                }
            } catch (ReflectiveOperationException | LinkageError ignored) {
                // every name is then UNKNOWN_<type>, and the line still has the packetType int
            }
            neiPacketNames = names;
        }
        final String name = names.get(type);
        return name != null ? name : "UNKNOWN_" + type;
    }

    // ------------------------------------------------------------------ FML handshake client mod list

    public static void storeClientModList(Object dispatcher, Map<String, String> mods) {
        if (dispatcher != null && mods != null) {
            CLIENT_MODS.put(dispatcher, new HashMap<>(mods));
        }
    }

    /**
     * Return null when the connection had no FML mod handshake, such as an integrated-server local channel or a
     * vanilla client.
     */
    public static Map<String, String> readClientMods(Object dispatcher) {
        return dispatcher == null ? null : CLIENT_MODS.get(dispatcher);
    }

    // ------------------------------------------------------------------ multiblocks

    /** Log a formation with no hatch information, such as a Railcraft multiblock or the bricked blast furnace. */
    public static void logMultiblockFormed(String kind, MachineSite site) {
        final SessionManager session = GtnhSpeedrunAudit.session();
        if (!(session != null && session.isFirstTime("mb:" + site.formatKey()))) {
            return;
        }
        session.logger()
            .log("multiblock_formed", describeFormation(session, kind, site));
    }

    /**
     * Log a formation once per session for each class, position and {@link MultiblockPower#dedupeSuffix}.
     *
     * <p>
     * A multiblock forms again each time its chunk reloads, because the MTE is recreated unformed and checks its
     * structure again. With this key, the formation is logged again after a swap of LV energy hatches for UV energy
     * hatches, and not after a chunk reload. After a server restart, every formed multiblock is logged again.
     * {@link #logGtMultiblockFormed} dedupes GT multiblocks across restarts. The {@code firstOfClass} flag comes from
     * the anchor, so the flag spans sessions and rewinds with the world.
     */
    public static void logMultiblockFormed(String kind, MachineSite site, MultiblockPower power) {
        final SessionManager session = GtnhSpeedrunAudit.session();
        if (!(session != null && session.isFirstTime("mb:" + site.formatKey() + power.dedupeSuffix()))) {
            return;
        }
        final JsonObject data = describeFormation(session, kind, site);
        power.writeTo(data);
        session.logger()
            .log("multiblock_formed", data);
    }

    /**
     * Log a GT multiblock formation, deduped by a marker that the controller saves in its NBT. The formation is logged
     * once, and again only when the hatch tiers, the amperages, or the coil or structure tiers change.
     *
     * <p>
     * A server restart or a chunk reload loads the marker back, so no formation is logged at the next check. A
     * restored backup restores the earlier marker, so a formation that the backup undid is logged again. The footprint
     * is on the line and not in the marker, so no formation is logged again for a footprint change alone.
     *
     * @param loggedMarker the controller's marker, null when the controller has never logged a formation
     * @return the marker to save, unchanged when nothing was logged
     */
    public static String logGtMultiblockFormed(String loggedMarker, GtFormation formation) {
        final SessionManager session = GtnhSpeedrunAudit.session();
        final String marker = formation.power()
            .dedupeSuffix();
        if (session == null || marker.equals(loggedMarker)) {
            return loggedMarker;
        }
        final JsonObject data = describeFormation(session, "gt", formation.site());
        formation.power()
            .writeTo(data);
        if (formation.footprint() != null) {
            formation.footprint()
                .writeTo(data);
        }
        session.logger()
            .log("multiblock_formed", data);
        return marker;
    }

    /**
     * Mark the class in the anchor's {@code formedMultiblocks} set, and return the start of the
     * {@code multiblock_formed} line.
     */
    private static JsonObject describeFormation(SessionManager session, String kind, MachineSite site) {
        final boolean firstOfClass = session.anchor().formedMultiblocks.add(site.className());
        if (firstOfClass) {
            session.anchor()
                .markDirty();
        }
        final JsonObject data = new JsonObject();
        data.addProperty("kind", kind);
        site.writeTo(data);
        data.addProperty("firstOfClass", firstOfClass);
        return data;
    }

    /**
     * Log the footprint of a formed GT multiblock whose footprint changed with no new formation. Examples are a wrench
     * rotation, an LSC that grew layers, and the first check after the mod version that added footprints.
     *
     * @return true when a line was logged
     */
    public static boolean logGtMultiblockFootprint(MachineSite site, MultiblockFootprint footprint) {
        final SessionManager session = GtnhSpeedrunAudit.session();
        if (session == null) {
            return false;
        }
        final JsonObject data = new JsonObject();
        site.writeTo(data);
        footprint.writeTo(data);
        session.logger()
            .log("multiblock_footprint", data);
        return true;
    }

    /** Log a GT machine explosion. A backup restore usually follows an explosion. */
    public static void logGtExplosion(MachineSite site, long power) {
        final SessionManager session = GtnhSpeedrunAudit.session();
        if (session == null) {
            return;
        }
        final JsonObject data = new JsonObject();
        site.writeTo(data);
        data.addProperty("power", power);
        session.logger()
            .log("gt_explosion", data);
    }

    /** Log a multiblock that stopped for lack of power, the event that GT's /powerfails lists. */
    public static void logGtPowerfail(MachineSite site) {
        final SessionManager session = GtnhSpeedrunAudit.session();
        if (session == null) {
            return;
        }
        final JsonObject data = new JsonObject();
        site.writeTo(data);
        session.logger()
            .log("gt_powerfail", data);
    }

    /**
     * Log a Blood Altar tier change after the altar checked its runes. In {@code upgradeLevel}, 0 is unchecked and 1
     * to 6 are tiers I to VI. The line has the altar's position and no class name.
     */
    public static void logBloodAltarTier(MachineSite site, int from, int to) {
        final SessionManager session = GtnhSpeedrunAudit.session();
        if (session == null) {
            return;
        }
        final JsonObject data = new JsonObject();
        site.writePositionTo(data);
        data.addProperty("from", from);
        data.addProperty("to", to);
        session.logger()
            .log("blood_altar_tier", data);
    }

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

    // ------------------------------------------------------------------ deaths

    /**
     * Return {@code player:<name>} for a player, the registered entity name such as {@code Zombie} for another
     * entity, or null for no entity.
     */
    public static String describeEntity(Entity entity) {
        if (entity == null) {
            return null;
        }
        if (entity instanceof EntityPlayer player) {
            return "player:" + player.getCommandSenderName();
        }
        final String registered = EntityList.getEntityString(entity);
        return registered != null ? registered
            : entity.getClass()
                .getSimpleName();
    }

    // ------------------------------------------------------------------ creative inventory

    /**
     * Log a creative-inventory packet: a slot of the player's inventory set to a stack, or a stack dropped with a slot
     * below 0. A packet that leaves the slot unchanged is not logged.
     *
     * <p>
     * A take from the creative tabs and a move between slots both arrive as slot sets, so the line has the slot's
     * previous contents too. A verifier nets {@code previousKey} against {@code itemKey} across a player's lines to
     * tell a spawn from a move. Vanilla ignores the packet from a player not in creative, and only a modified client
     * sends the packet outside creative. Such a packet is logged with {@code inCreative} false.
     */
    public static void logCreativeSlot(EntityPlayerMP player, SlotChange change, boolean inCreative) {
        final ItemStack stack = change.stack();
        final ItemStack previous = change.previous();
        final SessionManager session = GtnhSpeedrunAudit.session();
        if (session == null || ItemStack.areItemStacksEqual(stack, previous)) {
            return;
        }
        final JsonObject data = new JsonObject();
        data.addProperty(
            "uuid",
            player.getGameProfile()
                .getId()
                .toString());
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

    // ------------------------------------------------------------------ multiblock log failures

    // The server resolves the chat text, because a client may have no lang file for this server-only mod. Such a
    // client shows a translation component as its raw key. The Forge 1614 dedicated server loads
    // assets/gtnhspeedrunaudit/lang/en_US.lang into StringTranslate.

    /**
     * Report a multiblock formation or footprint change that this mod failed to log. A missing
     * {@code multiblock_formed} line reads as a multiblock that never formed. The failure is therefore logged once per
     * controller per session, and sent in red chat to every player once per controller class per session. The line has
     * no local name.
     *
     * @param what "formation" or "footprint"
     */
    public static void logMultiblockLogFailed(String what, MachineSite site, Throwable error) {
        final SessionManager session = GtnhSpeedrunAudit.session();
        if (session == null || !session.isFirstTime("mbfail:" + site.formatKey())) {
            return;
        }
        final JsonObject data = new JsonObject();
        data.addProperty("what", what);
        data.addProperty("class", site.className());
        site.writePositionTo(data);
        data.addProperty("error", String.valueOf(error));
        session.logger()
            .log("multiblock_log_failed", data);
        final MinecraftServer server = MinecraftServer.getServer();
        if (server == null || !session.isFirstTime("mbfail-chat:" + site.className())) {
            return;
        }
        final String key = "formation".equals(what) ? "gtnhspeedrunaudit.multiblock.formingNotRecorded"
            : "gtnhspeedrunaudit.multiblock.footprintNotRecorded";
        server.getConfigurationManager()
            .sendChatMsg(
                new ChatComponentText(
                    StatCollector.translateToLocalFormatted(
                        key,
                        site.className(),
                        site.dim(),
                        site.x(),
                        site.y(),
                        site.z(),
                        error)).setChatStyle(new ChatStyle().setColor(EnumChatFormatting.RED)));
    }

    // ------------------------------------------------------------------ AE2 census failures

    /**
     * Log a grid, or the whole grid list, that the AE2 census could not read. The method uses no AE2 types, so the
     * self-test calls the method on a server without AE2.
     *
     * @return a one-line description for {@link #announceAe2CensusFailure}
     */
    public static String logAe2CensusFailure(Ae2CensusFailure failure) {
        final String gridId = failure.gridId();
        GtnhSpeedrunAudit.LOG.error(
            "AE2 census {} failed ({}{})",
            failure.census(),
            failure.where(),
            gridId == null ? "" : " " + gridId,
            failure.error());
        final SessionManager session = GtnhSpeedrunAudit.session();
        if (session != null) {
            final JsonObject data = new JsonObject();
            data.addProperty("census", failure.census());
            data.addProperty("where", failure.where());
            if (gridId != null) {
                data.addProperty("gridId", gridId);
            }
            data.addProperty("error", String.valueOf(failure.error()));
            session.logger()
                .log("ae2_census_failed", data);
        }
        return (gridId == null ? failure.where() : failure.where() + " " + gridId) + ": " + failure.error();
    }

    /**
     * Send a red chat line to every player, in the style of the ServerUtilities message for a failed backup. The line
     * is about a failure of this mod's record of the network, so no player reads the line as a failure of AE2.
     */
    public static void announceAe2CensusFailure(List<String> failures) {
        final MinecraftServer server = MinecraftServer.getServer();
        if (server == null || failures.isEmpty()) {
            return;
        }
        final String detail = failures.size() == 1 ? failures.get(0)
            : StatCollector
                .translateToLocalFormatted("gtnhspeedrunaudit.ae2.censusFailures", failures.size(), failures.get(0));
        server.getConfigurationManager()
            .sendChatMsg(
                new ChatComponentText(
                    StatCollector.translateToLocalFormatted("gtnhspeedrunaudit.ae2.censusNotRecorded", detail))
                        .setChatStyle(new ChatStyle().setColor(EnumChatFormatting.RED)));
    }

    /**
     * The site, power and footprint of a GT multiblock at formation.
     *
     * @param footprint null when unknown
     */
    public record GtFormation(MachineSite site, MultiblockPower power, @Nullable MultiblockFootprint footprint) {}

    /**
     * The slot of a creative-inventory packet, with the stack that the packet sets and the stack the slot had.
     *
     * @param stack    null for an empty stack
     * @param previous null for an empty slot
     */
    public record SlotChange(int slot, @Nullable ItemStack stack, @Nullable ItemStack previous) {}

    /**
     * One failed read of an AE2 census.
     *
     * @param census the session's census number
     * @param where  {@code grid list} or {@code grid}
     * @param gridId null for the grid list, or for a grid whose id read failed
     */
    public record Ae2CensusFailure(int census, String where, @Nullable String gridId, Throwable error) {}

    /**
     * Build an {@code nei_cheat} line: a decoded cheat action of {@code NEIServerUtils}, with the detail that the
     * {@code nei_packet} line lacks. For a cheat with no player, such as NEI's time change, the line has a null
     * {@code uuid} and {@code name}.
     */
    public static final class NeiCheat {

        private final String action;
        private String uuid;
        private String name;
        private String itemKey;
        private int count;
        private String detail;
        private JsonObject extra;

        private NeiCheat(String action) {
            this.action = action;
        }

        public static NeiCheat of(String action) {
            return new NeiCheat(action);
        }

        public NeiCheat player(EntityPlayer player) {
            uuid = player.getGameProfile()
                .getId()
                .toString();
            name = player.getCommandSenderName();
            return this;
        }

        /** Set the item of the cheat. A null stack leaves the line with no {@code itemKey} and no {@code count}. */
        public NeiCheat item(@Nullable ItemStack stack) {
            if (stack != null) {
                item(ItemKey.formatBaseKey(stack), stack.stackSize);
            }
            return this;
        }

        public NeiCheat item(String key, int stackSize) {
            itemKey = key;
            count = stackSize;
            return this;
        }

        public NeiCheat detail(@Nullable String text) {
            detail = text;
            return this;
        }

        /**
         * Set the members that {@link #log} copies into the line, such as the previous slot contents, the deleted items
         * or the inserted count.
         */
        public NeiCheat extra(JsonObject members) {
            extra = members;
            return this;
        }

        /** Write the line when a session is logging. The members from {@code extra()} come after the fixed keys. */
        public void log() {
            final SessionManager session = GtnhSpeedrunAudit.session();
            if (session == null) {
                return;
            }
            final JsonObject data = new JsonObject();
            data.addProperty("action", action);
            data.addProperty("uuid", uuid);
            data.addProperty("name", name);
            if (itemKey != null) {
                data.addProperty("itemKey", itemKey);
                data.addProperty("count", count);
            }
            if (detail != null) {
                data.addProperty("detail", detail);
            }
            if (extra != null) {
                for (Map.Entry<String, JsonElement> member : extra.entrySet()) {
                    data.add(member.getKey(), member.getValue());
                }
            }
            session.logger()
                .log("nei_cheat", data);
        }
    }

    /**
     * Build an {@code nbt_edit} line for the applied half of /nbtedit: the packet whose NBT ServerUtilities writes into
     * a player, block, entity or item. The dispatch is in the {@code command} line.
     *
     * <p>
     * The line has the payload's hash, and the full payload unless the payload is oversized, so verifiers can see what
     * was set. The target's NBT just before the write is logged the same way, so verifiers can see what the write
     * replaced. Without {@link #before}, the line has the reason from {@link #beforeMissing}, such as an unloaded
     * chunk or a missing tile entity.
     */
    public static final class NbtEdit {

        private final String targetType;
        private final String target;
        private String uuid;
        private String name;
        private String nbtHash;
        private String nbtB64;
        private String beforeHash;
        private String beforeB64;
        private String beforeMissing;

        private NbtEdit(String targetType, String target) {
            this.targetType = targetType;
            this.target = target;
        }

        public static NbtEdit of(String targetType, String target) {
            return new NbtEdit(targetType, target);
        }

        public NbtEdit player(EntityPlayer player) {
            uuid = player.getGameProfile()
                .getId()
                .toString();
            name = player.getCommandSenderName();
            return this;
        }

        /** @param b64 null when the payload is oversized */
        public NbtEdit written(String hash, @Nullable String b64) {
            nbtHash = hash;
            nbtB64 = b64;
            return this;
        }

        /** @param b64 null when the NBT is oversized */
        public NbtEdit before(String hash, @Nullable String b64) {
            beforeHash = hash;
            beforeB64 = b64;
            return this;
        }

        public NbtEdit beforeMissing(@Nullable String reason) {
            beforeMissing = reason;
            return this;
        }

        /** Write the line when a session is logging. A null Base64, before-hash or reason is left off the line. */
        public void log() {
            final SessionManager session = GtnhSpeedrunAudit.session();
            if (session == null) {
                return;
            }
            final JsonObject data = new JsonObject();
            data.addProperty("targetType", targetType);
            data.addProperty("target", target);
            data.addProperty("uuid", uuid);
            data.addProperty("name", name);
            data.addProperty("nbtHash", nbtHash);
            if (nbtB64 != null) {
                data.addProperty("nbtB64", nbtB64);
            }
            if (beforeHash != null) {
                data.addProperty("beforeHash", beforeHash);
            }
            if (beforeB64 != null) {
                data.addProperty("beforeB64", beforeB64);
            }
            if (beforeMissing != null) {
                data.addProperty("beforeMissing", beforeMissing);
            }
            session.logger()
                .log("nbt_edit", data);
        }
    }

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
