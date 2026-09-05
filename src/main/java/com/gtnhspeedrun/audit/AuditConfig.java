package com.gtnhspeedrun.audit;

import java.io.File;

import net.minecraftforge.common.config.Configuration;

public final class AuditConfig {

    public static int invSnapshotMinutes = 15;
    public static int ae2SnapshotActiveMinutes = 60;
    public static int ae2SnapshotIdleMinutes = 240;
    public static boolean logPlayerIp = false;
    public static String[] keyItems = new String[0];

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
        keyItems = cfg.getStringList(
            "keyItems",
            "milestones",
            keyItems,
            "Items whose first appearance is a logged milestone, as registryName@meta (meta optional), e.g. "
                + "gregtech:gt.blockmachines@1000. Checked during inventory/AE2 snapshots and on craft.");

        if (cfg.hasChanged()) {
            cfg.save();
        }
    }
}
