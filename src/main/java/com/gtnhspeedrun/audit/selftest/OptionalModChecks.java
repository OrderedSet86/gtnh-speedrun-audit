package com.gtnhspeedrun.audit.selftest;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.gtnhspeedrun.audit.core.SessionManager;
import com.gtnhspeedrun.audit.harness.Ae2Fixture;
import com.gtnhspeedrun.audit.harness.FormationSideCheck;
import com.gtnhspeedrun.audit.harness.NeiDrill;
import com.gtnhspeedrun.audit.harness.PowerUseDrill;
import com.gtnhspeedrun.audit.harness.TecTechFixture;
import com.gtnhspeedrun.audit.trackers.GtMachineSites;
import com.gtnhspeedrun.audit.trackers.StructureTiers;

import cpw.mods.fml.common.Loader;

/** The self-test log checks that depend on which optional mods are loaded. */
public final class OptionalModChecks {

    // nothing in the scenario stocks this item, so the census entry of this item can come only from the pattern
    private static final String AE2_CRAFTABLE_KEY = "minecraft:nether_star@0";

    private final SessionManager session;
    private final CheckList checks;
    private final Scenario scenario;

    public OptionalModChecks(SessionManager session, CheckList checks, Scenario scenario) {
        this.session = session;
        this.checks = checks;
        this.scenario = scenario;
    }

    /**
     * Check the footprint of the Active Transformer's formation line: a 3×3×3 box that contains the controller and
     * the hatch, and the facing of the live controller. No footprint line is logged for the forced re-check at step
     * 30. One footprint line, with the new rotation and the same box, is logged for the rotation at step 31.
     */
    public void checkTecTechFootprint(JsonObject tecTech, List<JsonObject> lines) {
        final int BOUNDS_LENGTH = 6;
        final int[] box = new int[BOUNDS_LENGTH];
        final boolean hasBox = tecTech != null && tecTech.has("bounds")
            && tecTech.getAsJsonArray("bounds")
                .size() == BOUNDS_LENGTH;
        if (hasBox) {
            for (int i = 0; i < BOUNDS_LENGTH; i++) {
                box[i] = tecTech.getAsJsonArray("bounds")
                    .get(i)
                    .getAsInt();
            }
        }
        final int[] hatch = TecTechFixture.hatchPosition;
        final String[] tecTechFacing = scenario.getTecTechFacing();
        checks.check(
            "tectech footprint: 3x3x3 box around the controller and the hatch",
            hasBox && box[3] - box[0] == 2
                && box[4] - box[1] == 2
                && box[5] - box[2] == 2
                && isInBox(box, new int[] { scenario.offsetX(8), scenario.offsetY(0), scenario.offsetZ(8) })
                && hatch != null
                && isInBox(box, hatch),
            tecTech == null ? "no formation line"
                : String.valueOf(tecTech.get("bounds")) + " hatch=" + Arrays.toString(hatch));
        checks.check(
            "tectech footprint: facing, rotation and flip of the live controller",
            tecTech != null && tecTechFacing != null
                && tecTech.has("facing")
                && tecTechFacing[0].equals(
                    tecTech.get("facing")
                        .getAsString())
                && tecTech.has("rotation")
                && tecTechFacing[1].equals(
                    tecTech.get("rotation")
                        .getAsString())
                && tecTech.has("flip")
                && tecTechFacing[2].equals(
                    tecTech.get("flip")
                        .getAsString()),
            "line=" + tecTech + " live=" + Arrays.toString(tecTechFacing));
        final List<JsonObject> footprints = LogChecks.selectEventData(lines, "multiblock_footprint")
            .stream()
            .filter(
                data -> "MTEActiveTransformer".equals(
                    data.get("class")
                        .getAsString()))
            .collect(Collectors.toList());
        checks.check(
            "tectech footprint line only for the rotation, with the new rotation and the same box",
            footprints.size() == 1 && "CLOCKWISE".equals(
                footprints.get(0)
                    .get("rotation")
                    .getAsString())
                && tecTech != null
                && String.valueOf(tecTech.get("bounds"))
                    .equals(
                        String.valueOf(
                            footprints.get(0)
                                .get("bounds"))),
            footprints.toString());
    }

    /**
     * Test whether {@code point}, as {x, y, z}, lies in {@code box}, as {minX, minY, minZ, maxX, maxY, maxZ} with
     * both corners inclusive.
     */
    private static boolean isInBox(int[] box, int[] point) {
        return point[0] >= box[0] && point[1] >= box[1]
            && point[2] >= box[2]
            && point[0] <= box[3]
            && point[1] <= box[4]
            && point[2] <= box[5];
    }

    /**
     * Check one {@code nei_cheat} line for each cheat of {@link NeiDrill}. A cheat whose NEI method gets the player, or
     * that the drill sends through NEI's packet handler, is by Alice. The spawner and ghost-slot lines get Alice from
     * the packet, because those NEI methods get no player. The drill calls the rain toggle and the action lock outside
     * a packet, and those NEI methods get no player, so the lines of those two cheats have no player.
     */
    public void checkNeiDrill(List<JsonObject> lines) {
        if (!scenario.hasNeiDrillRun()) {
            checks.check("nei drill", false, "NEI is loaded and the drill did not run");
            return;
        }
        final List<JsonObject> allCheats = LogChecks.selectEventData(lines, "nei_cheat");
        final List<JsonObject> cheats = allCheats.stream()
            .filter(
                data -> data.has("name") && "SelfTestAlice".equals(
                    data.get("name")
                        .getAsString()))
            .collect(Collectors.toList());
        final List<JsonObject> playerless = allCheats.stream()
            .filter(data -> !data.has("name"))
            .collect(Collectors.toList());
        final String[][] byAlice = { { "toggle_magnet", null }, { "cycle_creative_inv", "steps=0" },
            { "creative_plus", "close" }, { "set_spawner_mob", NeiDrill.SPAWNER_MOB }, { "set_dummy_slot", null } };
        for (String[] action : byAlice) {
            checks.check("nei drill: " + action[0] + " by alice", hasCheat(cheats, action), null);
        }
        final String[][] withNoPlayer = { { "toggle_rain", null }, { "disable_action", NeiDrill.DISABLED_ACTION } };
        for (String[] action : withNoPlayer) {
            checks.check(
                "nei drill: " + action[0] + " outside a packet, with no player",
                hasCheat(playerless, action),
                null);
        }
        final JsonObject ghost = cheats.stream()
            .filter(
                data -> "set_dummy_slot".equals(
                    data.get("action")
                        .getAsString()))
            .findFirst()
            .orElse(null);
        checks.check(
            "nei drill: set_dummy_slot has the stack, slot class and container",
            ghost != null && ghost.has("itemKey")
                && "minecraft:apple@0".equals(
                    ghost.get("itemKey")
                        .getAsString())
                && ghost.get("slotClass")
                    .getAsString()
                    .endsWith("SlotDummy")
                && ghost.has("container"),
            String.valueOf(ghost));
    }

    /**
     * Test for a line with the action {@code action[0]}, and the detail {@code action[1]} unless {@code action[1]} is
     * null.
     */
    private static boolean hasCheat(List<JsonObject> cheats, String[] action) {
        return cheats.stream()
            .anyMatch(
                data -> action[0].equals(
                    data.get("action")
                        .getAsString())
                    && (action[1] == null || (data.has("detail") && action[1].equals(
                        data.get("detail")
                            .getAsString()))));
    }

    /**
     * Load every mixin target, vanilla or of an installed mod, and check the target for an injected audit$ member. A
     * mixin applies when the target class loads, and the scenario never loads some targets, such as the trash slot, the
     * /inv view container and the edit handlers of BetterQuesting. A bad target would then crash a real game later, not
     * this boot.
     */
    public void checkMixinsApplied() {
        final String[][] targets = { { null, "net.minecraft.command.CommandHandler" },
            { null, "net.minecraft.entity.player.EntityPlayerMP" }, { null, "net.minecraft.server.MinecraftServer" },
            { null, "net.minecraft.network.rcon.RConConsoleSource" },
            { null, "net.minecraft.network.NetHandlerPlayServer" }, { null, "net.minecraft.world.WorldServer" },
            { null, "net.minecraft.server.management.ServerConfigurationManager" },
            { "NotEnoughItems", "codechicken.nei.NEIServerUtils" }, { "NotEnoughItems", "codechicken.nei.NEISPH" },
            { "NotEnoughItems", "codechicken.nei.NEIServerConfig" },
            { "gregtech", "gregtech.common.data.GTPowerfailTracker" },
            { "gregtech", "gregtech.api.metatileentity.implementations.MTEMultiBlockBase" },
            { "gregtech", "gregtech.api.metatileentity.MetaTileEntity" },
            { "gregtech", "gregtech.api.metatileentity.MetaPipeEntity" },
            { "gregtech", "gregtech.api.metatileentity.BaseTileEntity" },
            { "gregtech", "gregtech.api.metatileentity.BaseMetaTileEntity" },
            { "gregtech", "gregtech.api.metatileentity.implementations.MTEBasicMachine" },
            { "gregtech", "gregtech.common.misc.WirelessNetworkManager" },
            { "gregtech", "gregtech.common.blocks.BlockMachines" }, { "gregtech", "detrav.net.DetravNetwork" },
            { "gregtech", "detrav.items.behaviours.BehaviourDetravToolProspector" },
            { "gregtech", "detrav.items.behaviours.BehaviourDetravToolElectricProspector" },
            { "gregtech", "gregtech.common.items.behaviors.BehaviourProspecting" },
            { "gregtech", "gregtech.common.tileentities.machines.basic.MTEAdvSeismicProspector" },
            { "gregtech", "gregtech.api.util.GTUtility$ItemNBT" },
            { "visualprospecting", "com.sinthoras.visualprospecting.VisualProspecting_API$LogicalServer" },
            { "gregtech", "gregtech.common.tileentities.machines.MTEHatchCraftingInputME" },
            { "appliedenergistics2", "appeng.container.implementations.ContainerPatternTerm" },
            { "appliedenergistics2", "appeng.container.implementations.ContainerPatternTermEx" },
            { "appliedenergistics2", "appeng.helpers.DualityInterface" },
            { "appliedenergistics2", "appeng.container.implementations.ContainerInterfaceTerminal" },
            { "gregtech", "tectech.thing.metaTileEntity.multi.base.TTMultiblockBase" },
            { "structurelib", "com.gtnewhorizon.structurelib.structure.StructureUtility" },
            { "betterquesting", "betterquesting.api2.utils.ParticipantInfo" },
            { "betterquesting", "bq_standard.tasks.base.TaskBase" },
            { "betterquesting", "betterquesting.network.handlers.NetQuestEdit" },
            { "betterquesting", "betterquesting.network.handlers.NetChapterEdit" },
            { "betterquesting", "betterquesting.network.handlers.NetImport" },
            { "betterquesting", "betterquesting.network.handlers.NetSettingSync" },
            { "AWWayofTime", "WayofTime.alchemicalWizardry.common.tileEntity.TEAltar" },
            { "ExtraUtilities", "com.rwtema.extrautils.gui.SlotTrash" },
            { "serverutilities", "serverutils.invsee.InvseeContainer" },
            { "serverutilities", "serverutils.net.MessageEditNBTResponse" },
            { "serverutilities", "serverutils.task.TeleportTask" },
            { "serverutilities", "serverutils.command.tp.CmdTplast" } };
        for (String[] target : targets) {
            if (target[0] != null && !Loader.isModLoaded(target[0])) {
                continue;
            }
            try {
                final Class<?> targetClass = Class.forName(target[1], true, SelfTest.class.getClassLoader());
                boolean injected = false;
                for (Method method : targetClass.getDeclaredMethods()) {
                    injected |= method.getName()
                        .contains("audit$");
                }
                for (Field field : targetClass.getDeclaredFields()) {
                    injected |= field.getName()
                        .contains("audit$");
                }
                checks.check("mixin applied: " + target[1], injected, injected ? null : "no audit$ member");
            } catch (ClassNotFoundException | LinkageError e) {
                checks.check("mixin applied: " + target[1], false, String.valueOf(e));
            }
        }
    }

    /**
     * Check the tier fields that {@link StructureTiers#planKeys} returns for real controller classes. A renamed GT
     * field then fails this check, and the tier does not go missing from a run's log with no warning. The classes
     * load without their static initialisers.
     */
    public void checkStructureTierPlans() {
        final String[][] expected = { { "gtnhintergalactic.tile.multi.elevator.TileEntitySpaceElevator", "motorTier" },
            { "gtnhintergalactic.tile.multi.elevatormodules.TileEntityModuleMiner$TileEntityModuleMinerT2", "tTier",
                "tModuleTier" },
            { "tectech.thing.metaTileEntity.multi.MTEEyeOfHarmony", "spacetimeCompressionFieldMetadata",
                "timeAccelerationFieldMetadata", "stabilisationFieldMetadata" },
            { "kubatech.tileentity.gregtech.multiblock.MTEDEFusionCrafter", "mTierCasing", "mFusionTierCasing" },
            { "gregtech.common.tileentities.machines.multi.foundry.MTEExoFoundry", "foundryData.tier",
                "foundryData.modules" },
            { "gregtech.common.tileentities.machines.multi.MTEPlasmaForge", "convergence", "mCoilLevel" },
            { "gtPlusPlus.xmod.gregtech.common.tileentities.machines.multi.production.MTEQuantumForceTransformer",
                "mCraftingTier", "mFocusingTier" },
            { "gregtech.common.tileentities.machines.multi.MTENanoForge", "mSpecialTier" },
            // on a dedicated server, the fields of this class can't all be listed, so StructureTiers looks up
            // glassTier by name
            { "kubatech.tileentity.gregtech.multiblock.MTEExtremeEntityCrusher", "glassTier" } };
        // GT 5.09.51.482 of pack 2.8.4 has none of these classes. On pack 2.8.4, the FML version string of GT is
        // "MC1710", and a version compare can't order that string against a GT release
        final Set<String> newerThanPack284 = Set
            .of("gregtech.common.tileentities.machines.multi.foundry.MTEExoFoundry");
        final boolean pack284Gt = !GtMachineSites.hasLocalNameKeyGetter();
        for (String[] row : expected) {
            final String checkName = "structure tiers planned: " + row[0].substring(row[0].lastIndexOf('.') + 1);
            try {
                final List<String> keys = StructureTiers
                    .planKeys(Class.forName(row[0], false, SelfTest.class.getClassLoader()));
                boolean all = true;
                for (int i = 1; i < row.length; i++) {
                    all &= keys.contains(row[i]);
                }
                checks.check(checkName, all, keys.toString());
            } catch (ClassNotFoundException e) {
                final boolean expectedAbsent = pack284Gt && newerThanPack284.contains(row[0]);
                checks.check(checkName, expectedAbsent, expectedAbsent ? "absent in GT 5.09.51" : String.valueOf(e));
            } catch (LinkageError e) {
                checks.check(checkName, false, String.valueOf(e));
            }
        }
    }

    /**
     * Check that the tiers of every registered multiblock read without a throw on this side, and that the Extreme
     * Entity Crusher of step 3 logged one formation line with a glass tier. On a pass, the check detail has the
     * classes whose fields can't all be listed on this side.
     */
    public void checkStructureTiersOnThisSide(List<JsonObject> lines) {
        final JsonObject crusher = LogChecks.selectEventData(lines, "multiblock_formed")
            .stream()
            .filter(
                data -> "MTEExtremeEntityCrusher".equals(
                    data.get("class")
                        .getAsString()))
            .findFirst()
            .orElse(null);
        checks.check(
            "extreme entity crusher line carries its glass tier, read by name on this side",
            crusher != null && !crusher.has("structureUnreadable")
                && crusher.has("structure")
                && crusher.getAsJsonObject("structure")
                    .has("glassTier"),
            crusher == null ? "no line" : String.valueOf(crusher));
        final List<String> failed = FormationSideCheck.readEveryMultiblock();
        checks.check(
            "structure tiers read on every multiblock",
            failed.isEmpty(),
            failed.isEmpty() ? "fields not listable on this side: " + FormationSideCheck.findUnlistableMultiblocks()
                : failed.toString());
        final long crusherFormations = LogChecks.selectEventData(lines, "multiblock_formed")
            .stream()
            .filter(
                data -> "MTEExtremeEntityCrusher".equals(
                    data.get("class")
                        .getAsString())
                    && data.get("x")
                        .getAsInt() == scenario.offsetX(-6)
                    && data.get("z")
                        .getAsInt() == scenario.offsetZ(-6))
            .count();
        checks.check(
            "extreme entity crusher formation logged once",
            crusherFormations == 1,
            "count=" + crusherFormations);
    }

    /**
     * Check the removals of {@code Scenario.removeGtHulls}: the breakBlock mixin logs the real bronze hulls' removals
     * with GT's machine key, the hull at x -12 with the cause {@code other}, and the hull at x -14 with the cause
     * {@code vajra}, alice and the Vajra.
     */
    public void checkGtHullRemoval(List<JsonObject> lines) {
        final List<String> hulls = new ArrayList<>();
        for (JsonObject data : LogChecks.selectEventData(lines, "machine_removed")) {
            if (!(data.get("y")
                .getAsInt() == scenario.offsetY(3)
                && data.get("z")
                    .getAsInt() == scenario.offsetZ(0)
                && data.has("machineKey"))) {
                continue;
            }
            hulls.add(
                (data.get("x")
                    .getAsInt() - scenario.offsetX(0)) + " "
                    + data.get("cause")
                        .getAsString()
                    + " "
                    + data.get("machineId")
                        .getAsInt()
                    + " "
                    + data.get("machineKey")
                        .getAsString()
                    + (data.has("tool") ? " " + data.get("tool")
                        .getAsString() : "")
                    + (data.has("name") ? " " + data.get("name")
                        .getAsString() : ""));
        }
        final String hull = Scenario.BRONZE_HULL_META + " gt.blockmachines.hull.bronze";
        checks.check(
            "breakBlock mixin: real GT hull removals logged with the key, a Vajra removal with the player",
            hulls.equals(
                List.of("-12 other " + hull, "-14 vajra " + hull + " " + Scenario.GT_VAJRA + "@0 SelfTestAlice")),
            hulls.toString());
    }

    /** Check that the pattern output appears in an AE2 census file as a count-0 entry flagged craftable. */
    public void checkAe2Craftable(List<JsonObject> lines) {
        final List<String> seen = new ArrayList<>();
        for (JsonObject data : LogChecks.selectEventData(lines, "ae2_snapshot")) {
            final Path snapshotPath = session.snapshotDir()
                .resolve(
                    data.get("file")
                        .getAsString());
            try {
                final JsonObject snapshot = readSnapshotFile(data);
                for (JsonElement element : snapshot.getAsJsonArray("items")) {
                    final JsonObject item = element.getAsJsonObject();
                    if (AE2_CRAFTABLE_KEY.equals(
                        item.get("key")
                            .getAsString())) {
                        final boolean ok = item.get("count")
                            .getAsLong() == 0 && item.has("craftable")
                            && data.get("craftableItems")
                                .getAsInt() >= 1;
                        checks.check("ae2 census flags the pattern output craftable", ok, item + " line=" + data);
                        return;
                    }
                }
                seen.add(snapshotPath.getFileName() + " craftableItems=" + data.get("craftableItems"));
            } catch (Exception e) {
                seen.add(snapshotPath.getFileName() + " unreadable: " + e);
            }
        }
        checks
            .check("ae2 census flags the pattern output craftable", false, AE2_CRAFTABLE_KEY + " absent from " + seen);
    }

    /**
     * Check the fixture's pattern: a {@code pattern_slot} line with the author, the census line's channel and pattern
     * counts, and in a census file a provider slot with the author whose pattern's output is the craftable item.
     */
    public void checkAe2Patterns(List<JsonObject> lines) {
        checks.check(
            "pattern_slot: the fixture's pattern put into the interface, with its author",
            LogChecks.selectEventData(lines, "pattern_slot")
                .stream()
                .anyMatch(
                    data -> data.has("author") && Ae2Fixture.PATTERN_AUTHOR.equals(
                        data.get("author")
                            .getAsString())),
            String.valueOf(LogChecks.selectEventData(lines, "pattern_slot")));
        final List<String> seen = new ArrayList<>();
        for (JsonObject data : LogChecks.selectEventData(lines, "ae2_snapshot")) {
            if (!(data.has("channelsInUse") && data.has("patterns")
                && data.get("patterns")
                    .getAsInt() >= 1)) {
                seen.add(String.valueOf(data));
                continue;
            }
            final Path snapshotPath = session.snapshotDir()
                .resolve(
                    data.get("file")
                        .getAsString());
            try (InputStream in = new GZIPInputStream(Files.newInputStream(snapshotPath))) {
                final JsonObject snapshot = new JsonParser().parse(new InputStreamReader(in, StandardCharsets.UTF_8))
                    .getAsJsonObject();
                if (hasAuthoredPattern(snapshot)) {
                    checks.check("ae2 census: a provider slot with the author and the pattern's output", true, null);
                    return;
                }
                seen.add(snapshotPath.getFileName() + " " + snapshot.get("providers") + " " + snapshot.get("patterns"));
            } catch (IOException | RuntimeException e) { // noqa: NAR142, degrade and report over census files
                seen.add(snapshotPath.getFileName() + " unreadable: " + e);
            }
        }
        checks.check("ae2 census: a provider slot with the author and the pattern's output", false, seen.toString());
    }

    /**
     * Return whether {@code snapshot} has a provider slot with the author {@link Ae2Fixture#PATTERN_AUTHOR} and a
     * pattern that contains {@link #AE2_CRAFTABLE_KEY}.
     */
    private static boolean hasAuthoredPattern(JsonObject snapshot) {
        for (JsonElement provider : snapshot.getAsJsonArray("providers")) {
            for (JsonElement rawSlot : provider.getAsJsonObject()
                .getAsJsonArray("slots")) {
                final JsonObject slot = rawSlot.getAsJsonObject();
                if (!(slot.has("author") && Ae2Fixture.PATTERN_AUTHOR.equals(
                    slot.get("author")
                        .getAsString())
                    && slot.has("pattern"))) {
                    continue;
                }
                final JsonObject pattern = snapshot.getAsJsonArray("patterns")
                    .get(
                        slot.get("pattern")
                            .getAsInt())
                    .getAsJsonObject();
                if (pattern.toString()
                    .contains(AE2_CRAFTABLE_KEY)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Check the files of every power_use line, with the intervals summed. The furnace's type consumed EU and smelted
     * cobblestone. The Active Transformer's controller is charged the EU that {@link PowerUseDrill} drained and no
     * more, so the EU the transformer moves from the hatch into the controller's buffer is charged nothing. Each
     * line's eu is the sum of the file's types.
     */
    public void checkPowerUse(List<JsonObject> lines) {
        final String SMELTED_KEY = "minecraft:stone@0";
        final String NO_OUTPUT = "none";
        final String furnaceType = scenario.getPowerUseFurnaceType();
        final String transformerType = scenario.getPowerUseTransformerType();
        final int[] controller = scenario.readTecTechControllerPosition();
        BigInteger furnaceEu = BigInteger.ZERO;
        BigInteger smeltEu = BigInteger.ZERO;
        long smeltStarts = 0;
        long routingStarts = 0;
        BigInteger transformerEu = BigInteger.ZERO;
        final List<String> mismatchedLines = new ArrayList<>();
        final List<String> unreadable = new ArrayList<>();
        for (JsonObject data : LogChecks.selectEventData(lines, "power_use")) {
            final JsonObject file;
            try {
                file = readSnapshotFile(data);
            } catch (IOException | JsonParseException | IllegalStateException e) {
                unreadable.add(data.get("file") + ": " + e);
                continue;
            }
            BigInteger typesEu = BigInteger.ZERO;
            for (JsonElement element : file.getAsJsonArray("types")) {
                final JsonObject type = element.getAsJsonObject();
                final BigInteger eu = type.get("eu")
                    .getAsBigInteger();
                typesEu = typesEu.add(eu);
                if (type.get("machine")
                    .getAsString()
                    .equals(furnaceType)) {
                    furnaceEu = furnaceEu.add(eu);
                }
            }
            if (!typesEu.equals(
                data.get("eu")
                    .getAsBigInteger())) {
                mismatchedLines.add(data.get("file") + " line=" + data.get("eu") + " types=" + typesEu);
            }
            for (JsonElement element : file.getAsJsonArray("multis")) {
                final JsonObject multi = element.getAsJsonObject();
                if (multi.get("dim")
                    .getAsInt() == 0
                    && multi.get("x")
                        .getAsInt() == controller[0]
                    && multi.get("y")
                        .getAsInt() == controller[1]
                    && multi.get("z")
                        .getAsInt() == controller[2]) {
                    transformerEu = transformerEu.add(
                        multi.get("eu")
                            .getAsBigInteger());
                }
            }
            for (JsonElement element : file.getAsJsonArray("recipes")) {
                final JsonObject recipe = element.getAsJsonObject();
                final String machine = recipe.get("machine")
                    .getAsString();
                final String output = recipe.get("output")
                    .getAsString();
                if (machine.equals(furnaceType) && output.equals(SMELTED_KEY)) {
                    smeltStarts += recipe.get("starts")
                        .getAsLong();
                    smeltEu = smeltEu.add(
                        recipe.get("eu")
                            .getAsBigInteger());
                } else if (machine.equals(transformerType) && output.equals(NO_OUTPUT)) {
                    routingStarts += recipe.get("starts")
                        .getAsLong();
                }
            }
        }
        checks.check("power_use files readable", unreadable.isEmpty(), String.valueOf(unreadable));
        checks
            .check("power_use line eu is the sum of the types", mismatchedLines.isEmpty(), mismatchedLines.toString());
        checks.check(
            "power_use: the furnace consumed EU and started a cobblestone smelt with EU",
            furnaceEu.signum() > 0 && smeltStarts >= 1 && smeltEu.signum() > 0,
            "type=" + furnaceType + " eu=" + furnaceEu + " starts=" + smeltStarts + " recipeEu=" + smeltEu);
        final BigInteger drained = BigInteger.valueOf(PowerUseDrill.DRAIN_EU * scenario.getPowerUseDrains());
        checks.check(
            "power_use: the active transformer is charged exactly the drained EU",
            transformerEu.equals(drained),
            "eu=" + transformerEu + " drained=" + drained);
        checks.check(
            "power_use: the active transformer's routing recipe starts are tallied",
            routingStarts >= 1,
            "type=" + transformerType + " starts=" + routingStarts);
    }

    private JsonObject readSnapshotFile(JsonObject data) throws IOException {
        final Path snapshotPath = session.snapshotDir()
            .resolve(
                data.get("file")
                    .getAsString());
        try (InputStream in = new GZIPInputStream(Files.newInputStream(snapshotPath))) {
            return new JsonParser().parse(new InputStreamReader(in, StandardCharsets.UTF_8))
                .getAsJsonObject();
        }
    }

    /**
     * Check the one worldedit_edit line of the fixture's edit session: the placed and removed block counts, and the
     * chest's tile entity.
     */
    public void checkWorldEdit(List<JsonObject> lines) {
        final List<JsonObject> edits = LogChecks.selectEventData(lines, "worldedit_edit");
        if (edits.size() != 1) {
            checks.check("worldedit_edit: one line for the fixture's session", false, "lines=" + edits);
            return;
        }
        final JsonObject edit = edits.get(0);
        final JsonObject placed = edit.getAsJsonObject("placed");
        final JsonObject removed = edit.getAsJsonObject("removed");
        final boolean ok = edit.get("changed")
            .getAsLong() == 6
            && placed.entrySet()
                .size() == 3
            && placed.get("minecraft:stone@0")
                .getAsLong() == 3
            && placed.get("minecraft:glass@0")
                .getAsLong() == 2
            && placed.get("minecraft:chest@0")
                .getAsLong() == 1
            && removed.entrySet()
                .size() == 1
            && removed.get("minecraft:air@0")
                .getAsLong() == 6
            && edit.get("tileEntitiesTotal")
                .getAsInt() == 1
            && edit.getAsJsonArray("tileEntities")
                .get(0)
                .getAsJsonArray()
                .get(3)
                .getAsString()
                .equals("minecraft:chest@0")
            && edit.get("dim")
                .getAsInt() == 0;
        checks.check("worldedit_edit: exact placed/removed counts and the chest's tile", ok, String.valueOf(edit));
    }
}
