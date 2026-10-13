package com.gtnhspeedrun.audit.compat;

import java.lang.reflect.Field;
import java.math.BigInteger;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.minecraft.server.MinecraftServer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.WorldServer;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.gtnhspeedrun.audit.core.AuditLogger;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.common.misc.GlobalVariableStorage;

/**
 * Periodic record of stored power: every Lapotronic Supercapacitor in loaded chunks (stored EU, capacity, the
 * capacitors installed per tier, wireless mode) and every team's wireless EU. Answers "how much power did they
 * have at time T" and "when was the first UHV capacitor installed" to within one interval.
 *
 * <p>
 * The LSC keeps all of it in private fields, read by reflection: `stored`, `capacity`, `capacitors`,
 * `wireless_mode` and `passiveDischargeAmount` have the same names from GT 5.09.51.482 (pack 2.8.4) through
 * current, and `capacitors` is indexed by the order of the LSC's own Capacitor enum (IV, LuV, ZPM, UV, UHV, None,
 * EV, UEV, UIV, UMV), whose names label the counts. The counts come from the last structure check, so they hold
 * only while the LSC is formed. GlobalVariableStorage.GlobalEnergy is keyed by team leader and has the same shape
 * in both versions.
 *
 * <p>
 * Loaded chunks only: an LSC in an unloaded chunk doesn't tick, so its charge can't change until it loads again.
 * The walk is the loaded tile list, once per interval, on the server thread.
 */
public final class GtPowerCensus {

    private static final String[] LSC_FIELDS = { "stored", "capacity", "capacitors", "wireless_mode",
        "passiveDischargeAmount" };

    private final AuditLogger logger;
    private final MinecraftServer server;
    private final Set<String> classes = new HashSet<>();
    private final long intervalTicks;
    private final Map<Class<?>, LscAccess> access = new HashMap<>();
    private long tickCounter;

    public GtPowerCensus(AuditLogger logger, MinecraftServer server, String[] lscClasses, int minutes) {
        this.logger = logger;
        this.server = server;
        for (String c : lscClasses) {
            if (!c.trim()
                .isEmpty()) {
                classes.add(c.trim());
            }
        }
        this.intervalTicks = minutes * 60L * 20L;
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        if (++tickCounter >= intervalTicks) {
            census("interval");
        }
    }

    /** Also wired to /audit snapshot. */
    public void census(String trigger) {
        tickCounter = 0;
        final JsonArray lscs = new JsonArray();
        BigInteger lscStored = BigInteger.ZERO;
        int readErrors = 0;
        for (WorldServer world : server.worldServers) {
            if (world == null) {
                continue;
            }
            @SuppressWarnings("unchecked")
            final List<TileEntity> tiles = world.loadedTileEntityList;
            for (TileEntity te : tiles) {
                if (!(te instanceof IGregTechTileEntity gte)) {
                    continue;
                }
                final Object mte = gte.getMetaTileEntity();
                if (mte == null || !classes.contains(
                    mte.getClass()
                        .getName())) {
                    continue;
                }
                final JsonObject o = describeLsc(mte, world.provider.dimensionId, gte, te);
                if (o.has("readError")) {
                    readErrors++;
                }
                if (o.has("stored")) {
                    lscStored = lscStored.add(
                        new BigInteger(
                            o.get("stored")
                                .getAsString()));
                }
                lscs.add(o);
            }
        }

        final JsonArray wireless = new JsonArray();
        BigInteger wirelessTotal = BigInteger.ZERO;
        for (Map.Entry<UUID, BigInteger> e : GlobalVariableStorage.GlobalEnergy.entrySet()) {
            final JsonObject o = new JsonObject();
            o.addProperty("teamLeader", String.valueOf(e.getKey()));
            o.addProperty("eu", String.valueOf(e.getValue()));
            wireless.add(o);
            if (e.getValue() != null) {
                wirelessTotal = wirelessTotal.add(e.getValue());
            }
        }

        // EU totals are decimal strings: they pass 2^63 in the endgame
        final JsonObject ref = new JsonObject();
        ref.addProperty("trigger", trigger);
        ref.addProperty("lscs", lscs.size());
        ref.addProperty("lscStored", lscStored.toString());
        ref.addProperty("readErrors", readErrors);
        ref.addProperty("wirelessTeams", wireless.size());
        ref.addProperty("wirelessStored", wirelessTotal.toString());
        final JsonObject file = new JsonObject();
        file.add("lscs", lscs);
        file.add("wireless", wireless);
        logger.logSnapshot(
            "power_census",
            String.format(
                "power-%d.json.gz",
                logger.clock()
                    .get()),
            () -> file,
            ref);
    }

    private JsonObject describeLsc(Object mte, int dim, IGregTechTileEntity gte, TileEntity te) {
        final JsonObject o = new JsonObject();
        o.addProperty("dim", dim);
        o.addProperty("x", te.xCoord);
        o.addProperty("y", te.yCoord);
        o.addProperty("z", te.zCoord);
        o.addProperty("owner", String.valueOf(gte.getOwnerUuid()));
        final LscAccess fields = access.computeIfAbsent(mte.getClass(), LscAccess::resolve);
        if (fields.error != null) {
            o.addProperty("readError", fields.error);
            return o;
        }
        try {
            o.addProperty("formed", fields.formed != null && fields.formed.getBoolean(mte));
            o.addProperty("stored", String.valueOf(fields.stored.get(mte)));
            o.addProperty("capacity", String.valueOf(fields.capacity.get(mte)));
            o.addProperty("wirelessMode", fields.wirelessMode.getBoolean(mte));
            o.addProperty("passiveDrain", fields.passiveDrain.getLong(mte));
            final int[] counts = (int[]) fields.capacitors.get(mte);
            final JsonObject capacitors = new JsonObject();
            for (int i = 0; i < counts.length; i++) {
                if (counts[i] != 0) {
                    capacitors.addProperty(i < fields.tierNames.length ? fields.tierNames[i] : "#" + i, counts[i]);
                }
            }
            o.add("capacitors", capacitors);
        } catch (ReflectiveOperationException | RuntimeException e) {
            o.addProperty("readError", String.valueOf(e));
        }
        return o;
    }

    /** The LSC fields of one class, resolved once. */
    private static final class LscAccess {

        Field stored;
        Field capacity;
        Field capacitors;
        Field wirelessMode;
        Field passiveDrain;
        /** MTEMultiBlockBase.mMachine: whether the last structure check passed. */
        Field formed;
        String[] tierNames = new String[0];
        String error;

        static LscAccess resolve(Class<?> lsc) {
            final LscAccess a = new LscAccess();
            try {
                final Field[] found = new Field[LSC_FIELDS.length];
                for (int i = 0; i < LSC_FIELDS.length; i++) {
                    found[i] = lsc.getDeclaredField(LSC_FIELDS[i]);
                    found[i].setAccessible(true);
                }
                a.stored = found[0];
                a.capacity = found[1];
                a.capacitors = found[2];
                a.wirelessMode = found[3];
                a.passiveDrain = found[4];
                a.formed = findField(lsc, "mMachine");
                final Class<?> tiers = Class.forName(lsc.getName() + "$Capacitor", false, lsc.getClassLoader());
                final Object[] values = tiers.getEnumConstants();
                a.tierNames = new String[values.length];
                for (int i = 0; i < values.length; i++) {
                    a.tierNames[i] = ((Enum<?>) values[i]).name();
                }
            } catch (ReflectiveOperationException | RuntimeException e) {
                a.error = String.valueOf(e);
            }
            return a;
        }

        private static Field findField(Class<?> start, String name) {
            for (Class<?> c = start; c != null; c = c.getSuperclass()) {
                try {
                    final Field f = c.getDeclaredField(name);
                    f.setAccessible(true);
                    return f;
                } catch (NoSuchFieldException ignored) {
                    // declared further up
                }
            }
            return null;
        }
    }
}
