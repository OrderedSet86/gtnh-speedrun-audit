package com.gtnhspeedrun.audit.snapshot;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/**
 * Interval driver for the periodic snapshots. Runs off the audit tick so pausing the integrated server pauses
 * the cadence with it. AE2's census (phase 4) plugs in as another periodic action with its own online/idle
 * cadence.
 */
public final class SnapshotScheduler {

    private final MinecraftServer server;
    private final InventorySnapshotter inventory;
    private final long invIntervalTicks;
    private long tickCounter;

    public SnapshotScheduler(MinecraftServer server, InventorySnapshotter inventory, int invIntervalMinutes) {
        this.server = server;
        this.inventory = inventory;
        this.invIntervalTicks = invIntervalMinutes * 60L * 20L;
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        tickCounter++;
        if (tickCounter % invIntervalTicks == 0) {
            snapshotAllPlayers("interval");
        }
    }

    public void snapshotAllPlayers(String trigger) {
        for (Object o : server.getConfigurationManager().playerEntityList) {
            inventory.capture((EntityPlayerMP) o, trigger);
        }
    }
}
