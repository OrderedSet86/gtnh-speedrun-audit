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

        if (cfg.hasChanged()) {
            cfg.save();
        }
    }
}
