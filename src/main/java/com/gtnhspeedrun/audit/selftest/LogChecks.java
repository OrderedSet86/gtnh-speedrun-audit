package com.gtnhspeedrun.audit.selftest;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntityChest;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.gtnhspeedrun.audit.compat.Compat;
import com.gtnhspeedrun.audit.core.SessionManager;
import com.gtnhspeedrun.audit.core.Verifier;
import com.gtnhspeedrun.audit.snapshot.StorageCensus;

/** Check the session's JSONL log, read back after the last step, for the events that the scenario drove. */
public final class LogChecks {

    private static final List<String> EXPECTED_TYPES = List.of(
        "session_start",
        "player_join",
        "timing_started",
        "gamemode_change",
        "inv_snapshot",
        "key_item_first_seen",
        "command",
        "difficulty_change",
        "death",
        "achievement",
        "dim_first_visit",
        "key_item_craft",
        "nei_packet",
        "nei_cheat",
        "multiblock_formed",
        "gt_explosion",
        "nbt_edit",
        "ae2_census_failed",
        "multiblock_log_failed",
        "block_placed",
        "player_leave",
        "dim_change",
        "creative_slot",
        "world_saving",
        "gamerule_change",
        "command_output",
        "item_id_table",
        "gt_powerfail",
        "blood_altar_tier",
        "trash_insert",
        "invsee_open",
        "invsee_click",
        "invsee_close",
        "trash_can_closed",
        "watched_block",
        "node_census",
        "bulk_placed",
        "creative_block");

    private final SessionManager session;
    private final CheckList checks;
    private final Scenario scenario;
    private final OptionalModChecks optionalModChecks;

    public LogChecks(SessionManager session, CheckList checks, Scenario scenario) {
        this.session = session;
        this.checks = checks;
        this.scenario = scenario;
        this.optionalModChecks = new OptionalModChecks(session, checks, scenario);
    }

    /**
     * Drain the log writer, read every JSONL file of the session, and check the logged events. A log that cannot be
     * read fails the "log readable" check and skips the other checks.
     */
    public void verifyLog() {
        final long DRAIN_TIMEOUT_MS = 10_000;
        session.logger()
            .writer()
            .drain(DRAIN_TIMEOUT_MS);
        final Map<String, Integer> counts = new HashMap<>();
        final List<JsonObject> lines = new ArrayList<>();
        try (DirectoryStream<Path> logFiles = Files.newDirectoryStream(
            session.logDir(),
            path -> path.getFileName()
                .toString()
                .endsWith(".jsonl"))) {
            for (Path logFile : logFiles) {
                for (String rawLine : Files.readAllLines(logFile, StandardCharsets.UTF_8)) {
                    if (!rawLine.isEmpty()) {
                        final JsonObject line = new JsonParser().parse(rawLine)
                            .getAsJsonObject();
                        lines.add(line);
                        counts.merge(
                            line.get("t")
                                .getAsString(),
                            1,
                            Integer::sum);
                    }
                }
            }
        } catch (Exception e) {
            checks.check("log readable", false, String.valueOf(e));
            return;
        }

        for (String type : EXPECTED_TYPES) {
            checks.check("event logged: " + type, counts.getOrDefault(type, 0) > 0, null);
        }
        final List<JsonObject> machinePlaced = selectEventData(lines, "machine_placed");
        checks.check(
            "machine_placed only for the machine, not the vanilla block",
            machinePlaced.size() == 1,
            machinePlaced.toString());
        checks.check(
            "machine_placed by a tool: itemKey is the tool, machineId and machine are the machine",
            machinePlaced.size() == 1 && "minecraft:diamond_pickaxe@0".equals(
                machinePlaced.get(0)
                    .get("itemKey")
                    .getAsString())
                && machinePlaced.get(0)
                    .has("machineId")
                && machinePlaced.get(0)
                    .get("machineId")
                    .getAsInt() == 0
                && machinePlaced.get(0)
                    .has("machine")
                && new ItemStack(Blocks.iron_block).getDisplayName()
                    .equals(
                        machinePlaced.get(0)
                            .get("machine")
                            .getAsString()),
            machinePlaced.toString());
        checks.check(
            "block_placed only for tracked block ×2",
            counts.getOrDefault("block_placed", 0) == 2,
            "count=" + counts.getOrDefault("block_placed", 0));
        final List<String> achievements = selectEventData(lines, "achievement").stream()
            .map(
                data -> data.get("achId")
                    .getAsString() + " parentUnlocked="
                    + data.get("parentUnlocked")
                        .getAsBoolean())
            .collect(Collectors.toList());
        checks.check(
            "achievement logged with its parent state, a locked parent too",
            achievements.equals(
                List.of("achievement.openInventory parentUnlocked=true", "achievement.mineWood parentUnlocked=false")),
            achievements.toString());
        // only the step-2 stained glass: every later placement and break is in survival
        final List<String> creative = selectEventData(lines, "creative_block").stream()
            .map(
                data -> data.get("action")
                    .getAsString() + " "
                    + data.get("block")
                        .getAsString()
                    + "@"
                    + data.get("meta")
                        .getAsInt()
                    + " "
                    + data.get("name")
                        .getAsString())
            .collect(Collectors.toList());
        checks.check(
            "creative_block: the creative place and break, nothing in survival",
            creative.equals(
                Arrays.asList(
                    "place minecraft:stained_glass@5 SelfTestAlice",
                    "break minecraft:stained_glass@5 SelfTestAlice")),
            String.valueOf(creative));
        checks.check("both players joined", counts.getOrDefault("player_join", 0) >= 2, null);
        checks.check("gamemode changes ×2", counts.getOrDefault("gamemode_change", 0) >= 2, null);
        checks.check("difficulty changes ×2", counts.getOrDefault("difficulty_change", 0) >= 2, null);
        // count only the SelfTest classes. On a pack server a formation of the TecTech fixture is logged too, and
        // checked below
        final long selfTestFormations = selectEventData(lines, "multiblock_formed").stream()
            .filter(
                data -> data.get("class")
                    .getAsString()
                    .startsWith("SelfTest"))
            .count();
        checks.check(
            "multiblock dedupe, hatch-tier and coil-tier changes re-log, formation marker",
            selfTestFormations == 8,
            "count=" + selfTestFormations);
        final List<JsonObject> logFailures = selectEventData(lines, "multiblock_log_failed");
        checks.check(
            "multiblock_log_failed: one line for the broken controller, logged twice",
            logFailures.size() == 1 && "SelfTestBrokenController".equals(
                logFailures.get(0)
                    .get("class")
                    .getAsString())
                && "formation".equals(
                    logFailures.get(0)
                        .get("what")
                        .getAsString())
                && logFailures.get(0)
                    .get("x")
                    .getAsInt() == scenario.offsetX(-8),
            String.valueOf(logFailures));
        if (Compat.GT5.isLoaded()) {
            final long tecTechFormations = selectEventData(lines, "multiblock_formed").stream()
                .filter(
                    data -> "MTEActiveTransformer".equals(
                        data.get("class")
                            .getAsString())
                        && data.get("x")
                            .getAsInt() == scenario.offsetX(8)
                        && data.get("z")
                            .getAsInt() == scenario.offsetZ(8))
                .count();
            checks.check(
                "tectech multiblock formed on its own tick check, once",
                tecTechFormations == 1,
                "count=" + tecTechFormations);
            final JsonObject tecTech = selectEventData(lines, "multiblock_formed").stream()
                .filter(
                    data -> "MTEActiveTransformer".equals(
                        data.get("class")
                            .getAsString()))
                .findFirst()
                .orElse(null);
            optionalModChecks.checkTecTechFootprint(tecTech, lines);
            checks.check(
                "tectech multi-amp hatch read: EV at 4 amps",
                tecTech != null && tecTech.has("energyTier")
                    && "EV".equals(
                        tecTech.get("energyTier")
                            .getAsString())
                    && tecTech.has("energyAmps")
                    && tecTech.get("energyAmps")
                        .getAsLong() == 4,
                String.valueOf(tecTech));
        }
        final List<JsonObject> bulk = selectEventData(lines, "bulk_placed");
        checks.check(
            "manipulator: one bulk_placed, the placed spot listed, the existing machine counted",
            bulk.size() == 1 && bulk.get(0)
                .getAsJsonArray("machines")
                .toString()
                .equals("[[" + scenario.offsetX(20) + "," + scenario.offsetY(0) + "," + scenario.offsetZ(0) + ",0]]")
                && bulk.get(0)
                    .getAsJsonArray("pipes")
                    .size() == 0
                && bulk.get(0)
                    .get("checked")
                    .getAsInt() == 1
                && bulk.get(0)
                    .getAsJsonObject("names")
                    .has("0"),
            bulk.toString());
        checks.check(
            "gamerule_change off then on",
            counts.getOrDefault("gamerule_change", 0) == 2,
            "count=" + counts.getOrDefault("gamerule_change", 0));
        checks.check(
            "command_output only for allowlisted commands",
            counts.getOrDefault("command_output", 0) == 2 && selectEventData(lines, "command_output").stream()
                .allMatch(
                    data -> "gamerule".equals(
                        data.get("cmd")
                            .getAsString())
                        && data.getAsJsonArray("lines")
                            .size() > 0),
            "count=" + counts.getOrDefault("command_output", 0));
        checks.check(
            "watched_block place ×2 + break",
            counts.getOrDefault("watched_block", 0) == 3,
            "count=" + counts.getOrDefault("watched_block", 0));
        final Set<String> outcomes = new HashSet<>();
        for (JsonObject data : selectEventData(lines, "invsee_close")) {
            outcomes.add(
                data.get("outcome")
                    .getAsString());
        }
        checks.check(
            "invsee view outcomes",
            outcomes.containsAll(
                Arrays.asList(
                    "changes lost (target online at close)",
                    "saved, last writer wins",
                    "changes lost (view not closed)"))
                && counts.getOrDefault("invsee_close", 0) == 5,
            "outcomes=" + outcomes + " closes=" + counts.getOrDefault("invsee_close", 0));
        checks.check(
            "trash_can_closed carries the chest's contents",
            selectEventData(lines, "trash_can_closed").stream()
                .anyMatch(
                    data -> data.getAsJsonArray("items")
                        .size() == 1),
            null);
        checks.check(
            "node_census found the watched tile",
            selectEventData(lines, "node_census").stream()
                .anyMatch(
                    data -> data.get("nodes")
                        .getAsInt() >= 1),
            null);
        checks.check(
            "world_saving off line carries lastSave",
            selectEventData(lines, "world_saving").stream()
                .anyMatch(data -> data.has("lastSave")),
            null);
        checks.check(
            "creative_slot unchanged slot not logged",
            counts.getOrDefault("creative_slot", 0) == 1,
            "count=" + counts.getOrDefault("creative_slot", 0));
        final List<JsonObject> saving = selectEventData(lines, "world_saving");
        checks.check(
            "world_saving off then on",
            saving.size() == 2 && !saving.get(0)
                .get("enabled")
                .getAsBoolean()
                && saving.get(1)
                    .get("enabled")
                    .getAsBoolean(),
            "count=" + saving.size());
        checks.check(
            "death killer logged",
            selectEventData(lines, "death").stream()
                .anyMatch(
                    data -> data.has("killer") && "Zombie".equals(
                        data.get("killer")
                            .getAsString())),
            null);
        checks.check(
            "multiblock energyTier logged",
            selectEventData(lines, "multiblock_formed").stream()
                .anyMatch(
                    data -> data.has("energyTier") && "UV".equals(
                        data.get("energyTier")
                            .getAsString())),
            null);
        checks.check(
            "nbt_edit carries the before state",
            selectEventData(lines, "nbt_edit").stream()
                .anyMatch(
                    data -> data.has("beforeB64") && "cafebabe".equals(
                        data.get("beforeHash")
                            .getAsString())),
            null);
        final String[] nbtEditHashes = scenario.getNbtEditHashes();
        if (nbtEditHashes == null) {
            checks.check("nbt_edit mixin drill", true, "skipped: ServerUtilities not loaded");
        } else {
            final String drillTarget = "block " + scenario
                .offsetX(5) + "," + scenario.offsetY(0) + "," + scenario.offsetZ(0);
            checks.check(
                "nbt_edit mixin drill: before and after hashes",
                selectEventData(lines, "nbt_edit").stream()
                    .anyMatch(
                        data -> drillTarget.equals(
                            data.get("target")
                                .getAsString())
                            && data.has("beforeHash")
                            && nbtEditHashes[0].equals(
                                data.get("beforeHash")
                                    .getAsString())
                            && nbtEditHashes[1].equals(
                                data.get("nbtHash")
                                    .getAsString())),
                "expected before=" + nbtEditHashes[0] + " after=" + nbtEditHashes[1]);
            final ItemStack edited = ((TileEntityChest) session.server().worldServers[0]
                .getTileEntity(scenario.offsetX(5), scenario.offsetY(0), scenario.offsetZ(0))).getStackInSlot(0);
            checks.check(
                "nbt_edit mixin drill: edit applied",
                edited != null && edited.stackSize == 2,
                edited == null ? "slot empty" : "count=" + edited.stackSize);
        }
        optionalModChecks.checkMixinsApplied();
        // The checks below are for events of optional mods, so their event types are not in EXPECTED_TYPES. The jar
        // runs on servers without those mods, and there the events never appear.
        if (Compat.GT5.isLoaded()) {
            optionalModChecks.checkStructureTierPlans();
            optionalModChecks.checkStructureTiersOnThisSide(lines);
        }
        if (Compat.BETTER_QUESTING.isLoaded()) {
            checks.check("event logged: quest_db", counts.getOrDefault("quest_db", 0) > 0, null);
        }
        if (StorageCensus.hasAnythingToCensus()) {
            checks.check("event logged: storage_census", counts.getOrDefault("storage_census", 0) > 0, null);
        }
        if (Compat.GT5.isLoaded()) {
            final JsonObject power = selectEventData(lines, "power_census").stream()
                .findFirst()
                .orElse(null);
            checks.check(
                "power census read the LSC and wireless EU",
                power != null && power.get("lscs")
                    .getAsInt() >= 1
                    && power.get("readErrors")
                        .getAsInt() == 0
                    && new BigInteger(
                        power.get("wirelessStored")
                            .getAsString()).compareTo(BigInteger.valueOf(Scenario.SEEDED_WIRELESS_EU))
                        >= 0,
                String.valueOf(power));
        }
        if (Compat.AE2.isLoaded()) {
            checks.check(
                "event logged: ae2_snapshot",
                counts.getOrDefault("ae2_snapshot", 0) > 0,
                "count=" + counts.getOrDefault("ae2_snapshot", 0));
            optionalModChecks.checkAe2Craftable(lines);
        }
        if (Compat.WORLDEDIT.isLoaded()) {
            optionalModChecks.checkWorldEdit(lines);
        }

        boolean sawNonzeroBeforeTiming = false;
        boolean timingSeen = false;
        for (JsonObject line : lines) {
            final String eventType = line.get("t")
                .getAsString();
            if (eventType.equals("timing_started")) {
                timingSeen = true;
            }
            if (!timingSeen && line.get("ticks")
                .getAsLong() != 0) {
                sawNonzeroBeforeTiming = true;
            }
        }
        checks.check("no ticks before timing_started", !sawNonzeroBeforeTiming, null);

        try {
            final Verifier.Result result = Verifier.verify(
                session.logDir(),
                session.anchor().worldAuditUuid,
                session.logger()
                    .writer()
                    .publishedSeq());
            checks.check("chain verifies", "OK".equals(result.verdict()), result.verdict());
        } catch (Exception e) {
            checks.check("chain verifies", false, String.valueOf(e));
        }
    }

    /** Collect the {@code data} object of each line with event type {@code eventType}, in log order. */
    public static List<JsonObject> selectEventData(List<JsonObject> lines, String eventType) {
        return lines.stream()
            .filter(
                line -> eventType.equals(
                    line.get("t")
                        .getAsString()))
            .map(line -> line.getAsJsonObject("data"))
            .collect(Collectors.toList());
    }
}
