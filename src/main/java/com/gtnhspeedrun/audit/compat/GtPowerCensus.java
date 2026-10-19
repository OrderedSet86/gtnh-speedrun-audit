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
import com.gtnhspeedrun.audit.AuditConfig;
import com.gtnhspeedrun.audit.core.AuditLogger;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.common.misc.GlobalVariableStorage;

/**
 * Record the stored power of a run at each interval: every Lapotronic Supercapacitor (LSC) in a loaded chunk, with
 * stored EU, capacity, the capacitors installed per tier and the wireless mode, and every team's wireless EU. With
 * these lines, a verifier can tell how much power a team had at time T, and when the first UHV capacitor went in, to
 * within one interval.
 *
 * <p>
 * The LSC fields are private and read by reflection. The fields {@code stored}, {@code capacity},
 * {@code capacitors}, {@code wireless_mode} and {@code passiveDischargeAmount} have the same names from GT
 * 5.09.51.482 (pack 2.8.4) through current. The {@code capacitors} array is indexed in the order of the LSC's
 * nested {@code Capacitor} enum (IV, LuV, ZPM, UV, UHV, None, EV, UEV, UIV, UMV), and the key of each count is the
 * name of the matching enum constant. The LSC sets the counts in its structure check, so the counts are valid only
 * while the LSC is formed. {@code GlobalVariableStorage.GlobalEnergy} is keyed by team leader and has the same
 * shape in both versions.
 *
 * <p>
 * An LSC in an unloaded chunk doesn't tick, so the LSC's charge can't change until the chunk loads. The census walks
 * the loaded tile entity list once per interval, on the server thread.
 */
public final class GtPowerCensus {

    private final AuditLogger logger;
    private final MinecraftServer server;
    private final Set<String> lscClassNames = new HashSet<>();
    private final long intervalTicks;
    private final Map<Class<?>, LscAccess> accessByClass = new HashMap<>();
    private long tickCounter;

    /**
     * Read the LSC class names from {@link AuditConfig#powerCensusClasses}, with blank entries skipped, and the
     * interval in minutes from {@link AuditConfig#powerCensusMinutes}.
     */
    public GtPowerCensus(AuditLogger logger, MinecraftServer server) {
        this.logger = logger;
        this.server = server;
        for (String lscClass : AuditConfig.powerCensusClasses) {
            if (!lscClass.trim()
                .isEmpty()) {
                lscClassNames.add(lscClass.trim());
            }
        }
        this.intervalTicks = AuditConfig.powerCensusMinutes * 60L * 20L;
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        if (++tickCounter >= intervalTicks) {
            takeCensus("interval");
        }
    }

    /**
     * Write one {@code power_census} line and its snapshot file, and restart the interval. When an LSC can't be read,
     * that LSC's entry gets {@code readError}, and {@code readErrors} on the line is the number of such entries.
     */
    public void takeCensus(String trigger) {
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
                if (!(mte != null && lscClassNames.contains(
                    mte.getClass()
                        .getName()))) {
                    continue;
                }
                final JsonObject lsc = describeLsc(mte, gte, world.provider.dimensionId);
                if (lsc.has("readError")) {
                    readErrors++;
                }
                if (lsc.has("stored")) {
                    lscStored = lscStored.add(
                        new BigInteger(
                            lsc.get("stored")
                                .getAsString()));
                }
                lscs.add(lsc);
            }
        }

        final JsonArray wireless = new JsonArray();
        BigInteger wirelessTotal = BigInteger.ZERO;
        for (Map.Entry<UUID, BigInteger> team : GlobalVariableStorage.GlobalEnergy.entrySet()) {
            final JsonObject teamEnergy = new JsonObject();
            teamEnergy.addProperty("teamLeader", String.valueOf(team.getKey()));
            teamEnergy.addProperty("eu", String.valueOf(team.getValue()));
            wireless.add(teamEnergy);
            if (team.getValue() != null) {
                wirelessTotal = wirelessTotal.add(team.getValue());
            }
        }

        // EU totals are decimal strings because they pass 2^63 in the endgame
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
            new AuditLogger.Snapshot(
                "power_census",
                String.format(
                    "power-%d.json.gz",
                    logger.clock()
                        .get()),
                () -> file,
                ref));
    }

    /**
     * Build one entry of the {@code lscs} array. After a failed read, the entry has the properties read before the
     * failure, then {@code readError}.
     */
    private JsonObject describeLsc(Object mte, IGregTechTileEntity gte, int dim) {
        final JsonObject lsc = new JsonObject();
        lsc.addProperty("dim", dim);
        lsc.addProperty("x", gte.getXCoord());
        lsc.addProperty("y", (int) gte.getYCoord());
        lsc.addProperty("z", gte.getZCoord());
        lsc.addProperty("owner", String.valueOf(gte.getOwnerUuid()));
        final LscAccess fields = accessByClass.computeIfAbsent(mte.getClass(), LscAccess::resolve);
        if (fields.error != null) {
            lsc.addProperty("readError", fields.error);
            return lsc;
        }
        try {
            lsc.addProperty("formed", fields.formed != null && fields.formed.getBoolean(mte));
            lsc.addProperty("stored", String.valueOf(fields.stored.get(mte)));
            lsc.addProperty("capacity", String.valueOf(fields.capacity.get(mte)));
            lsc.addProperty("wirelessMode", fields.wirelessMode.getBoolean(mte));
            lsc.addProperty("passiveDrain", fields.passiveDrain.getLong(mte));
            final int[] counts = (int[]) fields.capacitors.get(mte);
            final JsonObject capacitors = new JsonObject();
            for (int i = 0; i < counts.length; i++) {
                if (counts[i] != 0) {
                    capacitors.addProperty(i < fields.tierNames.length ? fields.tierNames[i] : "#" + i, counts[i]);
                }
            }
            lsc.add("capacitors", capacitors);
        } catch (ReflectiveOperationException | RuntimeException e) { // noqa: NAR142, degrade and report over
                                                                      // reflection into GT code
            lsc.addProperty("readError", String.valueOf(e));
        }
        return lsc;
    }

    /** The LSC fields of one class, resolved once. */
    private static final class LscAccess {

        private Field stored;
        private Field capacity;
        private Field capacitors;
        private Field wirelessMode;
        private Field passiveDrain;
        /** {@code MTEMultiBlockBase.mMachine}: whether the last structure check passed. Null when not declared. */
        private Field formed;
        private String[] tierNames = new String[0];
        private String error;

        /**
         * Resolve the LSC fields of {@code lsc}. A failed lookup sets {@code error}, and the fields after the failed
         * lookup stay null.
         */
        private static LscAccess resolve(Class<?> lsc) {
            final LscAccess access = new LscAccess();
            try {
                access.stored = openDeclaredField(lsc, "stored");
                access.capacity = openDeclaredField(lsc, "capacity");
                access.capacitors = openDeclaredField(lsc, "capacitors");
                access.wirelessMode = openDeclaredField(lsc, "wireless_mode");
                access.passiveDrain = openDeclaredField(lsc, "passiveDischargeAmount");
                access.formed = findField(lsc, "mMachine");
                final Class<?> tiers = Class.forName(lsc.getName() + "$Capacitor", false, lsc.getClassLoader());
                final Object[] tierConstants = tiers.getEnumConstants();
                access.tierNames = new String[tierConstants.length];
                for (int i = 0; i < tierConstants.length; i++) {
                    access.tierNames[i] = ((Enum<?>) tierConstants[i]).name();
                }
            } catch (ReflectiveOperationException | RuntimeException e) { // noqa: NAR142, degrade and report over
                                                                          // reflection into GT code
                access.error = String.valueOf(e);
            }
            return access;
        }

        private static Field openDeclaredField(Class<?> owner, String name) throws NoSuchFieldException {
            final Field field = owner.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        }

        private static Field findField(Class<?> start, String name) {
            for (Class<?> c = start; c != null; c = c.getSuperclass()) {
                try {
                    return openDeclaredField(c, name);
                } catch (NoSuchFieldException ignored) {
                    // look in the superclass
                }
            }
            return null;
        }
    }
}
