package com.gtnhspeedrun.audit.compat;

import java.math.BigInteger;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.gtnhspeedrun.audit.AuditConfig;
import com.gtnhspeedrun.audit.GtnhSpeedrunAudit;
import com.gtnhspeedrun.audit.core.AuditLogger;
import com.gtnhspeedrun.audit.trackers.GtMachineSites;
import com.gtnhspeedrun.audit.trackers.MachineSite;
import com.gtnhspeedrun.audit.trackers.PowerUse;
import com.gtnhspeedrun.audit.trackers.PowerUseCounter;
import com.gtnhspeedrun.audit.trackers.PowerUseInterval;
import com.gtnhspeedrun.audit.trackers.RecipeKey;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.metatileentity.implementations.MTEMultiBlockBase;

/**
 * Write one {@code power_use} line per interval: the EU each GT machine type and each multiblock consumed, and the
 * recipe starts per machine type and first output. With these lines, a verifier can tell where a run's power went.
 * The charging rule is in {@link PowerUse}. The class references GT types, so construct GtPowerUse only when GregTech
 * is loaded.
 *
 * <p>
 * EU that a machine moves with setEUVar() is not seen. The Space Elevator modules and the Nanochip Assembly Complex
 * consume EU that way, and the Lapotronic Supercapacitor stores EU that way. The Active Transformer and the Power
 * Substation are listed with the EU they send to their dynamo hatches. Recipe EU is EU per tick times duration, counted
 * at the recipe start, so a recipe that power-fails counts in full. The machine totals have only the EU drained.
 */
public final class GtPowerUse {

    private static final String UNREADABLE_MACHINE = "unreadable";

    private final AuditLogger logger;
    private final long intervalTicks;
    private final PowerUse powerUse;
    private long tickCounter;
    private long fromTick;

    /** Start recording on this thread. Construct on the server thread, in FMLServerStartingEvent. */
    public GtPowerUse(AuditLogger logger) {
        this.logger = logger;
        this.intervalTicks = AuditConfig.powerUseMinutes * 60L * 20L;
        this.powerUse = PowerUse.start(Thread.currentThread());
        this.fromTick = logger.clock()
            .get();
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.START) {
            // a GT tile tick that threw left the tile's frame open. Close the frame, so other mods' drains are not
            // charged to that tile
            powerUse.clearFrame();
            return;
        }
        if (++tickCounter >= intervalTicks) {
            takeInterval("interval");
        }
    }

    /**
     * Write the {@code power_use} line and snapshot file of the interval since the last call, and start a new interval.
     * For an interval with no EU consumed and no recipe started, nothing is written.
     */
    public void takeInterval(String trigger) {
        tickCounter = 0;
        final long toTick = logger.clock()
            .get();
        final long intervalStart = fromTick;
        fromTick = toTick;
        final PowerUseInterval interval = powerUse.takeInterval(GtPowerUse::identifyTile);
        if (interval.isEmpty()) {
            return;
        }
        if (interval.readErrors() > 0) {
            GtnhSpeedrunAudit.LOG.warn("power_use.read_errors count={} trigger={}", interval.readErrors(), trigger);
        }
        final JsonObject file = new JsonObject();
        file.add("types", writeTypes(interval));
        file.add("multis", writeMultis(interval));
        file.add("recipes", writeRecipes(interval));
        // EU totals are decimal strings because they pass 2^63 in the endgame
        final JsonObject ref = new JsonObject();
        ref.addProperty("trigger", trigger);
        ref.addProperty("fromTick", intervalStart);
        ref.addProperty("toTick", toTick);
        ref.addProperty("eu", String.valueOf(interval.sumEu()));
        ref.addProperty("wirelessEu", String.valueOf(interval.sumWirelessEu()));
        ref.addProperty(
            "types",
            interval.types()
                .size());
        ref.addProperty(
            "multis",
            interval.multis()
                .size());
        ref.addProperty(
            "recipes",
            interval.recipes()
                .size());
        ref.addProperty("readErrors", interval.readErrors());
        logger.logSnapshot(new AuditLogger.Snapshot("power_use", "power-use-" + toTick, () -> file, ref));
    }

    /**
     * Name the tile of {@code counter}: the machine type, and the site for a multiblock controller. A tile that cannot
     * be read is the {@code unreadable} type and a read error.
     */
    private static PowerUse.TileIdentity identifyTile(PowerUseCounter counter) {
        try {
            final IGregTechTileEntity base = (IGregTechTileEntity) counter;
            final MachineSite site = GtMachineSites.readSite(base);
            final boolean isMultiblock = base.getMetaTileEntity() instanceof MTEMultiBlockBase;
            return new PowerUse.TileIdentity(site.formatTypeKey(), isMultiblock ? site : null);
        } catch (RuntimeException e) { // noqa: NAR142, degrade and report over GT machine code
            PowerUse.active()
                .noteReadError();
            GtnhSpeedrunAudit.LOG.debug(
                "power_use.machine_unreadable class={}",
                counter.getClass()
                    .getName(),
                e);
            return new PowerUse.TileIdentity(UNREADABLE_MACHINE, null);
        }
    }

    private static JsonArray writeTypes(PowerUseInterval interval) {
        final JsonArray types = new JsonArray();
        for (Map.Entry<String, PowerUseInterval.MachineTotal> entry : interval.types()
            .entrySet()) {
            final PowerUseInterval.MachineTotal total = entry.getValue();
            final JsonObject type = new JsonObject();
            type.addProperty("machine", entry.getKey());
            type.addProperty("machines", total.machines());
            writeEu(type, total.eu(), total.wirelessEu());
            types.add(type);
        }
        return types;
    }

    private static void writeEu(JsonObject entry, BigInteger eu, BigInteger wirelessEu) {
        entry.addProperty("eu", eu.toString());
        if (wirelessEu.signum() != 0) {
            entry.addProperty("wirelessEu", wirelessEu.toString());
        }
    }

    private static JsonArray writeMultis(PowerUseInterval interval) {
        final JsonArray multis = new JsonArray();
        for (PowerUseInterval.MultiTotal total : interval.multis()) {
            final JsonObject multi = new JsonObject();
            multi.addProperty(
                "machine",
                total.site()
                    .formatTypeKey());
            total.site()
                .writePositionTo(multi);
            writeEu(multi, total.eu(), total.wirelessEu());
            multis.add(multi);
        }
        return multis;
    }

    /** Write one entry per recipe key: machine, output, starts, outputAmount and eu. */
    private static JsonArray writeRecipes(PowerUseInterval interval) {
        final JsonArray recipes = new JsonArray();
        for (Map.Entry<RecipeKey, PowerUseInterval.RecipeTotal> entry : interval.recipes()
            .entrySet()) {
            final PowerUseInterval.RecipeTotal total = entry.getValue();
            final JsonObject recipe = new JsonObject();
            recipe.addProperty(
                "machine",
                entry.getKey()
                    .machine());
            recipe.addProperty(
                "output",
                entry.getKey()
                    .output());
            recipe.addProperty("starts", total.starts());
            recipe.addProperty("outputAmount", total.outputAmount());
            recipe.addProperty(
                "eu",
                total.eu()
                    .toString());
            recipes.add(recipe);
        }
        return recipes;
    }
}
