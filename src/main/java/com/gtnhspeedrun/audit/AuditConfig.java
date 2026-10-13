package com.gtnhspeedrun.audit;

import java.io.File;

import net.minecraftforge.common.config.Configuration;

public final class AuditConfig {

    public static int invSnapshotMinutes = 15;
    public static int ae2SnapshotActiveMinutes = 60;
    public static int ae2SnapshotIdleMinutes = 240;
    public static boolean logPlayerIp = false;
    public static String[] keyItems = new String[0];
    /** Empty by default: GTNH ships Schematica and WorldEdit with the pack, so presence-flagging them is noise. */
    public static String[] flagClientMods = new String[0];
    /** The whole Stargate structure: base, ring/chevron (one block, meta 0/1), DHD, both power units. */
    public static String[] trackedPlacements = new String[] { "SGCraft:stargateBase", "SGCraft:stargateRing",
        "SGCraft:stargateController", "SGCraft:ic2PowerUnit", "SGCraft:rfPowerUnit" };

    /**
     * Magic blocks whose placement and player breaking are logged as watched_block: Thaumcraft aura and energized
     * nodes (blockAiry 0/5), node jars (blockJar 2), node stabilizers and the transducer (blockStoneDevice
     * 9/10/11), the Thaumic Horizons Ethereal Shard, and Blood Magic altars and ritual stones.
     */
    public static String[] watchedBlocks = new String[] { "Thaumcraft:blockAiry@0", "Thaumcraft:blockAiry@5",
        "Thaumcraft:blockJar@2", "Thaumcraft:blockStoneDevice@9", "Thaumcraft:blockStoneDevice@10",
        "Thaumcraft:blockStoneDevice@11", "ThaumicHorizons:synthNode", "AWWayofTime:Altar", "AWWayofTime:masterStone",
        "AWWayofTime:ritualStone", "AWWayofTime:imperfectRitualStone" };
    /** Tile classes whose full NBT goes into every node census: Thaumcraft nodes, node jars, Ethereal Shards. */
    public static String[] nodeCensusTiles = new String[] { "thaumcraft.common.tiles.TileNode",
        "thaumcraft.common.tiles.TileNodeEnergized", "thaumcraft.common.tiles.TileJarNode",
        "com.kentington.thaumichorizons.common.tiles.TileSyntheticNode" };
    public static int nodeCensusMinutes = 15;
    /** GT MetaTileEntity class names read by the power census: the Lapotronic Supercapacitor. */
    public static String[] powerCensusClasses = new String[] {
        "kekztech.common.tileentities.MTELapotronicSuperCapacitor" };
    public static int powerCensusMinutes = 5;
    public static int storageCensusMinutes = 60;
    /**
     * Commands whose text output is captured into command_output, matched against "command" or "command
     * firstArg". Allowlist only: chat-like commands are never captured.
     */
    public static String[] commandOutputCapture = new String[] { "gt global_energy_display",
        "gregtech global_energy_display", "gamerule", "difficulty", "powerfails", "bq_admin", "mothership_info",
        "backup" };

    private AuditConfig() {}

    public static void synchronizeConfiguration(File configFile) {
        final Configuration cfg = new Configuration(configFile);

        invSnapshotMinutes = cfg.getInt(
            "invSnapshotMinutes",
            "snapshots",
            invSnapshotMinutes,
            1,
            1440,
            "Minutes between periodic player inventory snapshots (event-triggered ones are free).");
        ae2SnapshotActiveMinutes = cfg.getInt(
            "ae2SnapshotActiveMinutes",
            "snapshots",
            ae2SnapshotActiveMinutes,
            5,
            1440,
            "Minutes between AE2 network censuses while at least one player is online.");
        ae2SnapshotIdleMinutes = cfg.getInt(
            "ae2SnapshotIdleMinutes",
            "snapshots",
            ae2SnapshotIdleMinutes,
            5,
            10080,
            "Minutes between AE2 censuses while the server is empty. Item injection needs a player online, so an "
                + "idle server only needs a coarse baseline; AFK automation drift between censuses is expected.");
        logPlayerIp = cfg.getBoolean(
            "logPlayerIp",
            "privacy",
            logPlayerIp,
            "Include player IP addresses in join lines. Off by default: the bundle is submitted publicly.");
        flagClientMods = cfg.getStringList(
            "flagClientMods",
            "milestones",
            flagClientMods,
            "Case-insensitive substrings matched against joining clients' handshake mod lists; matches are "
                + "flagged in player_join lines and SUMMARY. Empty by default — the pack ships Schematica and "
                + "WorldEdit, so only list what the board actually bans. The full client mod list is logged "
                + "regardless (client_mods events), so verifiers can search it after the fact.");
        keyItems = cfg.getStringList(
            "keyItems",
            "milestones",
            keyItems,
            "Items whose first appearance is a logged milestone, as registryName@meta (meta optional), e.g. "
                + "gregtech:gt.blockmachines@1000. Checked during inventory/AE2 snapshots and on craft.");
        trackedPlacements = cfg.getStringList(
            "trackedPlacements",
            "milestones",
            trackedPlacements,
            "Block registry names (modid:name, exact) whose player placements are logged as block_placed "
                + "with coordinates. Defaults to the Stargate structure. GT machines need no entry — they "
                + "are always logged as machine_placed.");

        watchedBlocks = cfg.getStringList(
            "watchedBlocks",
            "milestones",
            watchedBlocks,
            "Blocks (modid:name, or modid:name@meta) whose player placement and breaking are logged as "
                + "watched_block with coordinates; a break also records the tile entity's NBT.");
        nodeCensusTiles = cfg.getStringList(
            "nodeCensusTiles",
            "snapshots",
            nodeCensusTiles,
            "Tile entity class names (exact) recorded with full NBT in each node census. Loaded chunks only.");
        nodeCensusMinutes = cfg.getInt(
            "nodeCensusMinutes",
            "snapshots",
            nodeCensusMinutes,
            1,
            1440,
            "Minutes between node censuses (Thaumcraft nodes, node jars, Ethereal Shards in loaded chunks).");
        powerCensusClasses = cfg.getStringList(
            "powerCensusClasses",
            "snapshots",
            powerCensusClasses,
            "GT MetaTileEntity class names (exact) read by each power census: stored EU, capacity, capacitors per "
                + "tier, wireless mode. Loaded chunks only.");
        powerCensusMinutes = cfg.getInt(
            "powerCensusMinutes",
            "snapshots",
            powerCensusMinutes,
            1,
            1440,
            "Minutes between power censuses (Lapotronic Supercapacitors in loaded chunks, every team's wireless EU).");
        storageCensusMinutes = cfg.getInt(
            "storageCensusMinutes",
            "snapshots",
            storageCensusMinutes,
            5,
            1440,
            "Minutes between storage censuses (EnderStorage chests/pouches, Backpack-mod world backpacks).");
        commandOutputCapture = cfg.getStringList(
            "commandOutputCapture",
            "privacy",
            commandOutputCapture,
            "Commands whose text replies are logged as command_output, as \"command\" or \"command firstArg\". "
                + "Keep chat commands (msg, tell, r, me, say) out: their output is player conversation.");

        if (cfg.hasChanged()) {
            cfg.save();
        }
    }
}
