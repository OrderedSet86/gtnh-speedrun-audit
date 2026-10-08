package com.gtnhspeedrun.audit.trackers;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChatStyle;
import net.minecraft.util.EnumChatFormatting;

import com.google.gson.JsonObject;
import com.gtnhspeedrun.audit.GtnhSpeedrunAudit;
import com.gtnhspeedrun.audit.core.SessionManager;
import com.gtnhspeedrun.audit.snapshot.ItemKey;

/**
 * Static entry points for the late mixins. Signatures use only vanilla/JDK types — the mixin computes any
 * modded-API values before calling in, so this class loads on packs missing any (or all) of the target mods.
 * Every method is null-session-safe and safe from netty/server threads alike (the logger is thread-safe and
 * dedupe is synchronized).
 */
public final class AuditSinks {

    private static volatile Map<Integer, String> neiPacketNames;
    private static volatile java.lang.reflect.Method gtNameMethod;
    private static volatile boolean gtNameUnavailable;

    private AuditSinks() {}

    /**
     * GT renamed MetaTileEntity.getLocalName() (≤5.09.51.x, pack 2.8.4) to getLocalNameKey() (5.09.54.x,
     * dailies). The mixins must run on both lines, so the name is resolved reflectively — a call compiled
     * against either one would NoSuchMethodError on the other.
     */
    public static String gtLocalName(Object mte) {
        if (gtNameUnavailable) {
            return null;
        }
        java.lang.reflect.Method m = gtNameMethod;
        if (m == null) {
            try {
                m = mte.getClass()
                    .getMethod("getLocalNameKey");
            } catch (NoSuchMethodException e) {
                try {
                    m = mte.getClass()
                        .getMethod("getLocalName");
                } catch (NoSuchMethodException e2) {
                    gtNameUnavailable = true;
                    return null;
                }
            }
            gtNameMethod = m;
        }
        try {
            return String.valueOf(m.invoke(mte));
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ NEI

    /** HEAD of NEISPH.handlePacket — before authentication, so DENIED attempts land here too. */
    public static void neiPacket(String uuid, String name, int type) {
        final SessionManager session = GtnhSpeedrunAudit.session();
        if (session == null) {
            return;
        }
        final JsonObject data = new JsonObject();
        data.addProperty("packetType", type);
        data.addProperty("packetName", neiPacketName(type));
        data.addProperty("uuid", uuid);
        data.addProperty("name", name);
        session.logger()
            .log("nei_packet", data);
    }

    /** Decoded cheat actions from NEIServerUtils — the "what exactly" companion to nei_packet. */
    public static void neiCheat(String action, String uuid, String name, String itemKey, int count, String detail) {
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
        session.logger()
            .log("nei_cheat", data);
    }

    /**
     * Only C2S packet types that reach the log matter; resolve names from NEI's own PacketIDs so a NEI update
     * that renumbers them stays truthful. Reflection is once, lazily, and only ever runs when NEI is present
     * (the caller is a NEI mixin).
     */
    private static String neiPacketName(int type) {
        Map<Integer, String> names = neiPacketNames;
        if (names == null) {
            names = new HashMap<>();
            try {
                final Class<?> c2s = Class.forName("codechicken.nei.PacketIDs$C2S");
                for (Field f : c2s.getFields()) {
                    if (Modifier.isStatic(f.getModifiers()) && f.getType() == int.class) {
                        names.put(f.getInt(null), f.getName());
                    }
                }
            } catch (ReflectiveOperationException | LinkageError ignored) {
                // Names stay numeric; the type int is still logged.
            }
            neiPacketNames = names;
        }
        final String name = names.get(type);
        return name != null ? name : "UNKNOWN_" + type;
    }

    // ------------------------------------------------------------------ FML handshake client mod list

    /**
     * 1.7.10 FML logs the client's handshake mod list and discards it; the handshake mixin parks it here,
     * keyed by connection, until PlayerLoggedInEvent can attach it to a player. Weak keys: a dropped
     * connection takes its entry with it. Netty-thread writes, server-thread reads — synchronized.
     */
    private static final Map<Object, Map<String, String>> CLIENT_MODS = java.util.Collections
        .synchronizedMap(new java.util.WeakHashMap<>());

    public static void clientModList(Object dispatcher, Map<String, String> mods) {
        if (dispatcher != null && mods != null) {
            CLIENT_MODS.put(dispatcher, new HashMap<>(mods));
        }
    }

    /** Null when the connection did no FML mod handshake (integrated-server local channel, vanilla client). */
    public static Map<String, String> takeClientMods(Object dispatcher) {
        return dispatcher == null ? null : CLIENT_MODS.get(dispatcher);
    }

    // ------------------------------------------------------------------ ServerUtilities /nbtedit

    /**
     * The applied half of /nbtedit. The command dispatch is already in the command log; this is the packet
     * that actually writes NBT into a player/block/entity/item, logged with the full payload (or its hash
     * alone when oversized) so verifiers can see exactly what was set.
     */
    public static void nbtEdit(String targetType, String targetDesc, String uuid, String name, String nbtHash,
        String nbtB64) {
        final SessionManager session = GtnhSpeedrunAudit.session();
        if (session == null) {
            return;
        }
        final JsonObject data = new JsonObject();
        data.addProperty("targetType", targetType);
        data.addProperty("target", targetDesc);
        data.addProperty("uuid", uuid);
        data.addProperty("name", name);
        data.addProperty("nbtHash", nbtHash);
        if (nbtB64 != null) {
            data.addProperty("nbtB64", nbtB64);
        }
        session.logger()
            .log("nbt_edit", data);
    }

    // ------------------------------------------------------------------ multiblocks

    /** A formation with no hatch information (Railcraft, the bricked blast furnace). */
    public static void multiblockFormed(String kind, String className, String localName, int dim, int x, int y, int z) {
        multiblockFormed(kind, className, localName, dim, x, y, z, null);
    }

    /**
     * A multiblock "forms" again every time its chunk reloads (the MTE is recreated unformed and rechecks), so
     * formations are deduped per (class, position, hatch tiers) per session: swapping LV energy hatches for UV
     * ones re-logs the formation, a chunk reload does not. The cross-session first-per-class milestone is
     * deduped through the anchor and rewinds with the world.
     */
    public static void multiblockFormed(String kind, String className, String localName, int dim, int x, int y, int z,
        MultiblockPower power) {
        final SessionManager session = GtnhSpeedrunAudit.session();
        if (session == null) {
            return;
        }
        final String key = "mb:" + className + "@" + dim + ":" + x + "," + y + "," + z;
        if (!session.once(power == null ? key : key + power.dedupeSuffix())) {
            return;
        }
        final boolean firstOfClass = session.anchor().formedMultiblocks.add(className);
        if (firstOfClass) {
            session.anchor()
                .markDirty();
        }
        final JsonObject data = new JsonObject();
        data.addProperty("kind", kind);
        data.addProperty("class", className);
        if (localName != null) {
            data.addProperty("localName", localName);
        }
        data.addProperty("dim", dim);
        data.addProperty("x", x);
        data.addProperty("y", y);
        data.addProperty("z", z);
        data.addProperty("firstOfClass", firstOfClass);
        if (power != null) {
            data.addProperty("energyHatches", power.energyHatches);
            if (power.energyTier != null) {
                data.addProperty("energyTier", power.energyTier);
            }
            data.addProperty("dynamoHatches", power.dynamoHatches);
            if (power.dynamoTier != null) {
                data.addProperty("dynamoTier", power.dynamoTier);
            }
        }
        session.logger()
            .log("multiblock_formed", data);
    }

    /** Context for verifiers: the usual reason a backup restore follows. */
    public static void gtExplosion(String className, String localName, int dim, int x, int y, int z, long power) {
        final SessionManager session = GtnhSpeedrunAudit.session();
        if (session == null) {
            return;
        }
        final JsonObject data = new JsonObject();
        data.addProperty("class", className);
        if (localName != null) {
            data.addProperty("localName", localName);
        }
        data.addProperty("dim", dim);
        data.addProperty("x", x);
        data.addProperty("y", y);
        data.addProperty("z", z);
        data.addProperty("power", power);
        session.logger()
            .log("gt_explosion", data);
    }

    // ------------------------------------------------------------------ deaths

    /** "player:Name" for a player, the registered entity name ("Zombie") otherwise, null for no entity. */
    public static String entityName(Entity entity) {
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
     * One creative-inventory packet: the client setting a slot of its own inventory to a stack, or dropping
     * one (slot < 0). Taking an item from the creative tabs and moving an item between slots both arrive as
     * slot sets, so the slot's previous contents are logged too: a verifier nets "previous" against "item"
     * across a player's lines to tell a spawn from a move. A packet from a player not in creative is ignored
     * by vanilla and can only come from a modified client — logged with inCreative=false. A packet that leaves
     * the slot as it was is not logged.
     */
    public static void creativeSlot(EntityPlayerMP player, int slot, ItemStack stack, ItemStack previous,
        boolean inCreative) {
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
        data.addProperty("slot", slot);
        if (stack != null && stack.getItem() != null) {
            data.addProperty("itemKey", ItemKey.base(stack));
            data.addProperty("count", stack.stackSize);
        }
        if (previous != null && previous.getItem() != null) {
            data.addProperty("previousKey", ItemKey.base(previous));
            data.addProperty("previousCount", previous.stackSize);
        }
        data.addProperty("inCreative", inCreative);
        data.addProperty("dim", player.dimension);
        session.logger()
            .log("creative_slot", data);
    }

    // ------------------------------------------------------------------ AE2 census failures

    /**
     * One grid (or the whole grid list) the AE2 census could not read. Lives here rather than in the compat
     * package so it stays AE2-free: SelfTest drives it on servers without AE2. Returns a one-line description
     * for {@link #announceAe2CensusFailure}.
     */
    public static String ae2CensusFailed(int census, String where, String gridId, Throwable error) {
        GtnhSpeedrunAudit.LOG
            .error("AE2 census {} failed ({}{})", census, where, gridId == null ? "" : " " + gridId, error);
        final SessionManager session = GtnhSpeedrunAudit.session();
        if (session != null) {
            final JsonObject data = new JsonObject();
            data.addProperty("census", census);
            data.addProperty("where", where);
            if (gridId != null) {
                data.addProperty("gridId", gridId);
            }
            data.addProperty("error", String.valueOf(error));
            session.logger()
                .log("ae2_census_failed", data);
        }
        return (gridId == null ? where : where + " " + gridId) + ": " + error;
    }

    /**
     * Red chat line to every player, the way ServerUtilities announces a failed backup. Worded so nobody reads
     * it as AE2 itself breaking: what failed is this mod's record of the network, not the network.
     */
    public static void announceAe2CensusFailure(List<String> failures) {
        final MinecraftServer server = MinecraftServer.getServer();
        if (server == null || failures.isEmpty()) {
            return;
        }
        final String detail = failures.size() == 1 ? failures.get(0)
            : failures.size() + " failures, first: " + failures.get(0);
        server.getConfigurationManager()
            .sendChatMsg(
                new ChatComponentText(
                    "[GTNH Speedrun Audit] The audit mod could not record its AE2 network snapshot (" + detail
                        + "). Your AE2 network is not affected. This run is missing AE2 evidence: please report "
                        + "this to @.order on Discord.")
                            .setChatStyle(new ChatStyle().setColor(EnumChatFormatting.RED)));
    }
}
