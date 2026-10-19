package com.gtnhspeedrun.audit.selftest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import net.minecraft.server.MinecraftServer;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.gtnhspeedrun.audit.GtnhSpeedrunAudit;
import com.gtnhspeedrun.audit.core.JsonUtil;
import com.gtnhspeedrun.audit.core.SessionManager;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/**
 * Run every loggable action with fake players on a live dev dedicated server, then read the JSONL log back and check
 * that the trackers logged each action. The harness runs only with {@code -Dspeedrunaudit.selftest=<resultDir>} set.
 * The drill script adds tests of the verdict across several boots.
 *
 * <p>
 * The run clocks, the movement detection and the gamemode and difficulty polls run on server ticks, so the server
 * has to tick between steps. The harness is a step machine on ServerTickEvent, with {@link #STEP_GAP_TICKS} ticks
 * between steps. Two fake players drive the multiplayer paths. On a pack server, the scenario runs with the mixins
 * applied.
 *
 * <p>
 * The harness writes {@code <resultDir>/selftest.json} and one stdout line, SELFTEST PASSED or SELFTEST FAILED.
 * Scripts check the file and the line, because the server shuts down normally and the exit code is the same for a pass
 * and a fail.
 */
public final class SelfTest {

    public static final String PROPERTY = "speedrunaudit.selftest";
    // stand-ins for the GT machine block and the Matter Manipulator, so the drill runs without either mod
    public static final String STAND_IN_MACHINE_BLOCK = "minecraft:iron_block";
    public static final String STAND_IN_MANIPULATOR_PREFIX = "minecraft:stick@";
    /** Every per-tick poll runs within this gap, so each poll reads the change of the step before. */
    private static final int STEP_GAP_TICKS = 5;

    private final MinecraftServer server;
    private final Path resultDir;
    private final CheckList checks = new CheckList();
    private final Scenario scenario;
    private final LogChecks logChecks;

    private int tick;
    private int step;
    private boolean done;

    /** Register {@code auditListeners}, the Forge and FML bus listeners of the audit, on the fake players' bus. */
    public SelfTest(SessionManager session, Path resultDir, List<Object> auditListeners) {
        this.server = session.server();
        this.resultDir = resultDir;
        this.scenario = new Scenario(session, checks, auditListeners);
        this.logChecks = new LogChecks(session, checks, scenario);
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || done) {
            return;
        }
        tick++;
        if (tick % STEP_GAP_TICKS != 0) {
            return;
        }
        try {
            if (scenario.runStep(step++)) {
                logChecks.verifyLog();
                finish();
            }
        } catch (RuntimeException | LinkageError e) { // noqa: NAR142, degrade and report over pack mod handlers
            // The check detail is one line. The stack trace in the log has the class of the mod handler that
            // threw.
            GtnhSpeedrunAudit.LOG.error("[selftest] step {} threw", step - 1, e);
            checks.check("step " + (step - 1) + " threw", false, String.valueOf(e));
            finish();
        }
    }

    /**
     * Write {@code selftest.json} to the result directory, log the SELFTEST PASSED or SELFTEST FAILED line, and shut
     * the server down. A failed report write is logged, and the shutdown still happens.
     */
    private void finish() {
        done = true;
        final List<String> failures = new ArrayList<>();
        final JsonArray checkArray = new JsonArray();
        final List<CheckList.Check> results = checks.getResults();
        for (CheckList.Check c : results) {
            final JsonObject checkJson = new JsonObject();
            checkJson.addProperty("name", c.name());
            checkJson.addProperty("ok", c.ok());
            if (!c.detail()
                .isEmpty()) {
                checkJson.addProperty("detail", c.detail());
            }
            checkArray.add(checkJson);
            if (!c.ok()) {
                failures.add(c.name());
            }
        }
        final JsonObject report = new JsonObject();
        report.addProperty("pass", failures.isEmpty());
        report.add("checks", checkArray);
        try {
            if (!Files.isDirectory(resultDir)) {
                Files.createDirectories(resultDir);
            }
            Files.write(
                resultDir.resolve("selftest.json"),
                JsonUtil.GSON.toJson(report)
                    .getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            GtnhSpeedrunAudit.LOG.error("[selftest] could not write report", e);
        }
        if (failures.isEmpty()) {
            GtnhSpeedrunAudit.LOG.info("SELFTEST PASSED ({} checks)", results.size());
        } else {
            GtnhSpeedrunAudit.LOG.error("SELFTEST FAILED: {}", String.join(", ", failures));
        }
        server.initiateShutdown();
    }
}
