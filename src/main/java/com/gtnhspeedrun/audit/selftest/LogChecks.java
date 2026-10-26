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
import net.minecraft.world.MinecraftException;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.gtnhspeedrun.audit.compat.Compat;
import com.gtnhspeedrun.audit.core.AttemptLimiter;
import com.gtnhspeedrun.audit.core.SessionManager;
import com.gtnhspeedrun.audit.core.Verifier;
import com.gtnhspeedrun.audit.harness.NbtEditDrill;
import com.gtnhspeedrun.audit.harness.QuestEditDrill;
import com.gtnhspeedrun.audit.harness.TeleportDrill;
import com.gtnhspeedrun.audit.snapshot.StorageCensus;
import com.gtnhspeedrun.audit.trackers.PositionRow;

/** Check the session's JSONL log, read back after the last step, for the events that the scenario drove. */
public final class LogChecks {

    private static final String ANCHOR_SYNC_MARKER = "selftest_anchor_sync";
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
        "world_saved",
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
        "creative_block",
        "machine_removed",
        "bulk_removed",
        "dim_table",
        "pos_sample");

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
        final long anchorSeqAfterSave = logMarkerAndSaveOverworld();
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
        final long markerSeq = lines.stream()
            .filter(
                line -> ANCHOR_SYNC_MARKER.equals(
                    line.get("t")
                        .getAsString()))
            .mapToLong(
                line -> line.get("seq")
                    .getAsLong())
            .findFirst()
            .orElse(Long.MAX_VALUE);
        checks.check(
            "anchor sync: a save publishes the anchor past the last logged line",
            anchorSeqAfterSave >= markerSeq,
            "anchor lastSeq=" + anchorSeqAfterSave + " marker seq=" + markerSeq);
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
        checkNameKeys(lines);
        checkRemovals(lines);
        checkDimensionTable(lines);
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
        final List<JsonElement> savedAnchors = new ArrayList<>();
        for (JsonObject saved : selectEventData(lines, "world_saved")) {
            savedAnchors.add(saved.get("anchor"));
        }
        checks.check(
            "world_saved has the anchor values of a world_saving lastSave",
            selectEventData(lines, "world_saving").stream()
                .anyMatch(off -> off.has("lastSave") && savedAnchors.contains(off.get("lastSave"))),
            "worldSaved=" + savedAnchors.size());
        final List<String> misnamedFiles = new ArrayList<>();
        for (JsonObject line : lines) {
            final JsonObject data = line.getAsJsonObject("data");
            if (data.has("fileSha256") && !data.get("file")
                .getAsString()
                .endsWith(
                    "-" + line.get("seq")
                        .getAsLong() + ".json.gz")) {
                misnamedFiles.add(
                    data.get("file")
                        .getAsString());
            }
        }
        checks.check(
            "snapshot file names end with the seq of the reference line",
            misnamedFiles.isEmpty(),
            String.valueOf(misnamedFiles));
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
            checkForgedNbtEditFlood(lines);
        }
        checkPlaces(lines);
        optionalModChecks.checkMixinsApplied();
        // The checks below are for events of optional mods, so their event types are not in EXPECTED_TYPES. The jar
        // runs on servers without those mods, and there the events never appear.
        if (Compat.GT5.isLoaded()) {
            optionalModChecks.checkStructureTierPlans();
            optionalModChecks.checkStructureTiersOnThisSide(lines);
            optionalModChecks.checkGtHullRemoval(lines);
        }
        if (Compat.BETTER_QUESTING.isLoaded()) {
            checks.check("event logged: quest_db", counts.getOrDefault("quest_db", 0) > 0, null);
            checkForgedQuestEditFlood(lines);
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

    /**
     * Log a marker line, then save the overworld, and return the anchor's lastSeq after the save, or -1 when the save
     * fails. Between the 100-tick publishes, only the publish at the start of the save covers the marker.
     */
    private long logMarkerAndSaveOverworld() {
        session.logger()
            .log(ANCHOR_SYNC_MARKER, new JsonObject());
        try {
            session.server().worldServers[0].saveAllChunks(false, null);
        } catch (MinecraftException e) {
            checks.check("anchor sync: overworld saved", false, String.valueOf(e));
            return -1;
        }
        return session.anchor().lastSeq;
    }

    /** Check the locale-free keys beside the display names of machines, achievements and dimensions. */
    private void checkNameKeys(List<JsonObject> lines) {
        final List<JsonObject> machinePlaced = selectEventData(lines, "machine_placed");
        checks.check(
            "machine_placed has the machine key",
            !machinePlaced.isEmpty() && machinePlaced.stream()
                .allMatch(data -> data.has("machineKey")),
            machinePlaced.toString());
        final List<String> achievementKeys = new ArrayList<>();
        for (JsonObject data : selectEventData(lines, "achievement")) {
            achievementKeys.add(
                data.has("achKey") ? data.get("achKey")
                    .getAsString() : "none");
        }
        checks.check(
            "achievement has the lang key",
            achievementKeys.equals(List.of("achievement.openInventory", "achievement.mineWood")),
            achievementKeys.toString());
        final List<JsonObject> visits = selectEventData(lines, "dim_first_visit");
        checks.check(
            "dim_first_visit has the provider class",
            !visits.isEmpty() && visits.stream()
                .allMatch(data -> data.has("provider")),
            visits.toString());
    }

    /**
     * Check the removals of {@code Scenario.driveRemovals}: spots 30, 31, 32, 34, 35, 36 and 37 as machine_removed
     * lines with the causes broken, wrenched, exploded, other, other, vajra and vajra, and spot 33 in one bulk_removed
     * line.
     */
    private void checkRemovals(List<JsonObject> lines) {
        final List<String> removals = new ArrayList<>();
        for (JsonObject data : selectEventData(lines, "machine_removed")) {
            if (!"SelfTestRemoved".equals(
                data.get("class")
                    .getAsString())) {
                continue;
            }
            removals.add(
                (data.get("x")
                    .getAsInt() - scenario.offsetX(0)) + " "
                    + data.get("cause")
                        .getAsString()
                    + " "
                    + data.get("kind")
                        .getAsString()
                    + (data.has("name") ? " " + data.get("name")
                        .getAsString() : "")
                    + (data.has("tool") ? " " + data.get("tool")
                        .getAsString() : "")
                    + (data.has("machineKey") ? " " + data.get("machineKey")
                        .getAsString() : ""));
        }
        checks.check(
            "machine_removed with each cause, the player and the tool",
            removals.equals(
                List.of(
                    "30 broken machine SelfTestAlice minecraft:diamond_pickaxe@0 tile.blockIron",
                    "31 wrenched machine SelfTestAlice minecraft:shears@0 tile.blockIron",
                    "32 exploded machine tile.blockIron",
                    "34 other machine tile.blockIron",
                    "35 other unknown",
                    "36 vajra machine SelfTestAlice minecraft:golden_pickaxe@0 tile.blockIron",
                    "37 vajra machine SelfTestAlice minecraft:golden_pickaxe@0 tile.blockIron")),
            removals.toString());
        final List<JsonObject> bulk = selectEventData(lines, "bulk_removed");
        checks.check(
            "manipulator removal: one bulk_removed with the removed spot",
            bulk.size() == 1 && bulk.get(0)
                .getAsJsonArray("machines")
                .toString()
                .equals("[[" + scenario.offsetX(33) + "," + scenario.offsetY(0) + "," + scenario.offsetZ(0) + ",0]]")
                && "tile.blockIron".equals(
                    bulk.get(0)
                        .getAsJsonObject("keys")
                        .get("0")
                        .getAsString()),
            bulk.toString());
    }

    private void checkDimensionTable(List<JsonObject> lines) {
        final List<String> vanillaRows = new ArrayList<>();
        for (JsonObject data : selectEventData(lines, "dim_table")) {
            for (JsonElement row : data.getAsJsonArray("dims")) {
                final int id = row.getAsJsonObject()
                    .get("id")
                    .getAsInt();
                if (-1 <= id && id <= 1) {
                    vanillaRows.add(
                        id + " "
                            + row.getAsJsonObject()
                                .has("provider"));
                }
            }
        }
        checks.check(
            "dim_table lists the vanilla dimensions with their providers",
            vanillaRows.equals(List.of("-1 true", "0 true", "1 true")),
            vanillaRows.toString());
    }

    /**
     * Check the forged /nbtedit replies of {@link NbtEditDrill#sendForgedFlood}: the hook takes under a second for the
     * large list, the count of lines is at the limit, and the rate_limited line counts the rest.
     */
    private void checkForgedNbtEditFlood(List<JsonObject> lines) {
        final long MAX_HOOK_MS = 1_000;
        final long floodMs = scenario.getNbtEditFloodMs();
        checks.check("nbt_edit forged flood: hook under 1 s", floodMs >= 0 && floodMs < MAX_HOOK_MS, floodMs + " ms");
        final long attemptLines = selectEventData(lines, "nbt_edit").stream()
            .filter(
                data -> data.has("beforeMissing") && "no pending edit".equals(
                    data.get("beforeMissing")
                        .getAsString()))
            .count();
        checks.check(
            "nbt_edit forged flood: limit of lines holds",
            attemptLines == AttemptLimiter.LINES_PER_WINDOW,
            "lines=" + attemptLines);
        final long dropped = selectEventData(lines, "rate_limited").stream()
            .filter(
                data -> "nbt_edit".equals(
                    data.get("type")
                        .getAsString()))
            .mapToLong(
                data -> data.get("dropped")
                    .getAsLong())
            .sum();
        final long expectedDropped = 1 + NbtEditDrill.FLOOD_BURST - AttemptLimiter.LINES_PER_WINDOW;
        checks.check("nbt_edit forged flood: the rest counted", dropped == expectedDropped, "dropped=" + dropped);
    }

    /**
     * Check the forged quest edits of {@link QuestEditDrill#sendForgedFlood}: the handler takes under a second for the
     * large list. For a sender who isn't an op, the rate_limited line counts the edits past the limit.
     */
    private void checkForgedQuestEditFlood(List<JsonObject> lines) {
        final long MAX_HOOK_MS = 1_000;
        final long floodMs = scenario.getQuestEditFloodMs();
        checks.check("quest_edit forged flood: hook under 1 s", floodMs >= 0 && floodMs < MAX_HOOK_MS, floodMs + " ms");
        if (scenario.isQuestEditFloodByOp()) {
            checks.check("quest_edit forged flood: limit", true, "skipped: the sender is an op");
            return;
        }
        final long dropped = selectEventData(lines, "rate_limited").stream()
            .filter(
                data -> "quest_edit".equals(
                    data.get("type")
                        .getAsString()))
            .mapToLong(
                data -> data.get("dropped")
                    .getAsLong())
            .sum();
        final long expectedDropped = 1 + NbtEditDrill.FLOOD_BURST - AttemptLimiter.LINES_PER_WINDOW;
        checks.check("quest_edit forged flood: the rest counted", dropped == expectedDropped, "dropped=" + dropped);
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

    /**
     * Check the position samples of alice and bob, the place in every inventory snapshot line, and on a pack server the
     * teleport lines of {@link TeleportDrill}. In the self-test, a survival player is sampled on every fifth tick and a
     * player in any other mode on every tick. The scenario runs one step on every fifth tick. Alice is in creative from
     * step 2 to step 3, and moves 2 blocks at step 5 and back at step 6. Bob never moves.
     */
    private void checkPlaces(List<JsonObject> lines) {
        final Map<Integer, List<PositionRow>> rowsByPlayer = selectPositionRows(lines);
        final List<PositionRow> aliceRows = rowsByPlayer
            .getOrDefault(findPlayerNumber(lines, "SelfTestAlice"), List.of());
        final List<PositionRow> bobRows = rowsByPlayer.getOrDefault(findPlayerNumber(lines, "SelfTestBob"), List.of());
        final List<String> aliceModes = new ArrayList<>();
        for (PositionRow row : aliceRows) {
            aliceModes.add(row.mode());
        }
        // the first sample, creative at step 2, survival at step 3, and the moves of steps 5 and 6. A FakePlayer joins
        // with no gamemode
        checks.check(
            "pos_sample: alice has a row at the first sample, each gamemode change and each move, and none while still",
            aliceModes.equals(List.of("", "c", "s", "s", "s")),
            "modes=" + aliceModes);
        checks.check("pos_sample: one row for bob, who never moves", bobRows.size() == 1, "rows=" + bobRows.size());
        checks.check(
            "pos_sample: yaw wrapped and pitch kept",
            !aliceRows.isEmpty() && aliceRows.stream()
                .allMatch(
                    row -> row.yaw() == Math.round(Scenario.ALICE_YAW)
                        && row.pitch() == Math.round(Scenario.ALICE_PITCH)),
            aliceRows.isEmpty() ? "no rows"
                : aliceRows.get(0)
                    .toString());

        final List<JsonObject> snapshots = selectEventData(lines, "inv_snapshot");
        checks.check(
            "inv_snapshot lines carry dim, pos, yaw and pitch",
            !snapshots.isEmpty() && snapshots.stream()
                .allMatch(
                    data -> data.has("dim") && data.has("pos")
                        && data.getAsJsonArray("pos")
                            .size() == 3
                        && data.has("yaw")
                        && data.has("pitch")),
            snapshots.isEmpty() ? "no snapshots"
                : snapshots.get(0)
                    .toString());
        final JsonObject aliceJoin = snapshots.stream()
            .filter(
                data -> "SelfTestAlice".equals(
                    data.get("name")
                        .getAsString())
                    && "join".equals(
                        data.get("trigger")
                            .getAsString()))
            .findFirst()
            .orElse(null);
        checks.check(
            "inv_snapshot join place is alice's spawn spot and facing",
            aliceJoin != null && aliceJoin.getAsJsonArray("pos")
                .toString()
                .equals(formatPosition(new double[] { scenario.offsetX(0), scenario.offsetY(1), scenario.offsetZ(0) }))
                && aliceJoin.get("yaw")
                    .getAsDouble() == Scenario.ALICE_YAW,
            String.valueOf(aliceJoin));

        final double[][] teleportFeet = scenario.getTeleportFeet();
        if (teleportFeet == null) {
            checks.check("teleport drill", true, "skipped: ServerUtilities not loaded");
            return;
        }
        final List<String> teleports = selectEventData(lines, "teleport").stream()
            .map(
                data -> data.get("type")
                    .getAsString() + " "
                    + data.getAsJsonObject("to")
                        .getAsJsonArray("pos"))
            .collect(Collectors.toList());
        checks.check(
            "teleport drill: a /home task and a /tpl, each with the destination",
            teleports
                .equals(List.of("HOME " + formatPosition(teleportFeet[0]), "TPL " + formatPosition(teleportFeet[1]))),
            String.valueOf(teleports));
        final JsonObject aliceLeave = snapshots.stream()
            .filter(
                data -> "SelfTestAlice".equals(
                    data.get("name")
                        .getAsString())
                    && "leave".equals(
                        data.get("trigger")
                            .getAsString()))
            .findFirst()
            .orElse(null);
        checks.check(
            "teleport drill: alice left at the /tpl spot",
            aliceLeave != null && aliceLeave.getAsJsonArray("pos")
                .toString()
                .equals(formatPosition(teleportFeet[1])),
            String.valueOf(aliceLeave));
    }

    /** Collect the rows of every {@code pos_sample} line by player number, in log order. */
    private static Map<Integer, List<PositionRow>> selectPositionRows(List<JsonObject> lines) {
        final Map<Integer, List<PositionRow>> rowsByPlayer = new HashMap<>();
        for (JsonObject data : selectEventData(lines, "pos_sample")) {
            for (JsonElement rawRow : data.getAsJsonArray("players")) {
                final PositionRow row = PositionRow.parseRow(rawRow.getAsJsonArray());
                rowsByPlayer.computeIfAbsent(row.player(), key -> new ArrayList<>())
                    .add(row);
            }
        }
        return rowsByPlayer;
    }

    /** Return the {@code playerNumber} of the first {@code player_join} of {@code name}, or -1 for no join. */
    private static int findPlayerNumber(List<JsonObject> lines, String name) {
        for (JsonObject data : selectEventData(lines, "player_join")) {
            if (name.equals(
                data.get("name")
                    .getAsString())) {
                return data.get("playerNumber")
                    .getAsInt();
            }
        }
        return -1;
    }

    private static String formatPosition(double[] feet) {
        return "[" + feet[0] + "," + feet[1] + "," + feet[2] + "]";
    }
}
