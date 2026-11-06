package com.gtnhspeedrun.audit.trackers;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.Map;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;

import org.jetbrains.annotations.Nullable;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.gtnhspeedrun.audit.GtnhSpeedrunAudit;
import com.gtnhspeedrun.audit.core.AttemptLimiter;
import com.gtnhspeedrun.audit.core.SessionManager;
import com.gtnhspeedrun.audit.snapshot.ItemKey;
import com.gtnhspeedrun.audit.snapshot.StackJson;

/**
 * Log the NEI packets and the decoded NEI cheat actions that the NEI mixins report.
 *
 * <p>
 * Signatures use only vanilla, JDK and audit types, so this class loads on a pack without NEI. Every log method returns
 * early when no session is logging, and can run on the netty thread or the server thread.
 */
public final class NeiSinks {

    // static: the mixins call these sinks with no instance. The cached reflection results stay valid for the
    // JVM's lifetime.
    private static volatile Map<Integer, String> neiPacketNames;
    // static: handlePacket() runs on one thread for each packet, and the mixins have no instance to pass the sender
    // through
    private static final ThreadLocal<EntityPlayerMP> PACKET_SENDER = new ThreadLocal<>();

    private NeiSinks() {}

    /**
     * Call at the head of {@code NEISPH.handlePacket}, with {@link #endPacket} at the return. An {@code nei_cheat} line
     * logged in between, from an NEI method that gets no player, has the sender's uuid and name.
     */
    public static void beginPacket(EntityPlayerMP sender) {
        PACKET_SENDER.set(sender);
    }

    public static void endPacket() {
        PACKET_SENDER.remove();
    }

    /**
     * Log an NEI ghost-slot write ({@code SET_DUMMY_SLOT}): the stack the client sent, the slot's stack before the
     * write, and the classes of the slot and of the sender's open container. Call just before the write.
     */
    public static void logDummySlot(Slot slot, @Nullable ItemStack stack) {
        final JsonObject extra = new JsonObject();
        extra.addProperty("slot", slot.slotNumber);
        extra.addProperty(
            "slotClass",
            slot.getClass()
                .getName());
        final EntityPlayerMP sender = PACKET_SENDER.get();
        if (sender != null) {
            extra.addProperty(
                "container",
                sender.openContainer.getClass()
                    .getName());
        }
        final JsonObject previous = StackJson.toJson(slot.getStack());
        if (previous != null) {
            extra.add("previous", previous);
        }
        if (stack != null && stack.hasTagCompound()) {
            extra.add("stack", StackJson.toJson(stack));
        }
        NeiCheat.of("set_dummy_slot")
            .item(stack)
            .extra(extra)
            .log();
    }

    /**
     * Call at the head of {@code NEISPH.handlePacket}. The head runs before authentication, so a denied packet is
     * logged too. Past the limit of {@link AttemptLimiter}, a packet is counted, not logged.
     */
    public static void logNeiPacket(String uuid, String name, int type) {
        final SessionManager session = GtnhSpeedrunAudit.session();
        if (!(session != null && session.attempts()
            .tryLog(uuid, name, "nei_packet"))) {
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

    /**
     * Build an {@code nei_cheat} line: a decoded cheat action of NEI, with the detail that the {@code nei_packet} line
     * lacks. A cheat with no player, such as NEI's time change, gets the sender of the NEI packet being handled.
     * Outside a packet, the line has a null {@code uuid} and {@code name}.
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
            final EntityPlayerMP sender = PACKET_SENDER.get();
            if (uuid == null && sender != null) {
                player(sender);
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
}
