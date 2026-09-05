package com.gtnhspeedrun.audit.trackers;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.Map;

import com.google.gson.JsonObject;
import com.gtnhspeedrun.audit.GtnhSpeedrunAudit;
import com.gtnhspeedrun.audit.core.SessionManager;

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

    // ------------------------------------------------------------------ multiblocks

    /**
     * A multiblock "forms" again every time its chunk reloads (the MTE is recreated unformed and rechecks), so
     * formations are deduped per (class, position) per session; the cross-session first-per-class milestone is
     * deduped through the anchor and rewinds with the world.
     */
    public static void multiblockFormed(String kind, String className, String localName, int dim, int x, int y, int z) {
        final SessionManager session = GtnhSpeedrunAudit.session();
        if (session == null) {
            return;
        }
        if (!session.once("mb:" + className + "@" + dim + ":" + x + "," + y + "," + z)) {
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
}
