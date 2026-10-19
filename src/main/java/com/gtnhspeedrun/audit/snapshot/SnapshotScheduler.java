package com.gtnhspeedrun.audit.snapshot;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/**
 * Take an inventory snapshot of every online player at a fixed interval.
 *
 * <p>
 * The interval counts server ticks, so the interval stops while an integrated server is paused.
 */
public final class SnapshotScheduler {

    private final MinecraftServer server;
    private final InventorySnapshotter inventory;
    private final long inventoryIntervalTicks;
    private long tickCounter;

    public SnapshotScheduler(MinecraftServer server, InventorySnapshotter inventory, int inventoryIntervalMinutes) {
        this.server = server;
        this.inventory = inventory;
        final long TICKS_PER_MINUTE = 60L * 20L;
        this.inventoryIntervalTicks = inventoryIntervalMinutes * TICKS_PER_MINUTE;
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        tickCounter++;
        if (tickCounter % inventoryIntervalTicks == 0) {
            snapshotAllPlayers("interval");
        }
    }

    public void snapshotAllPlayers(String trigger) {
        for (Object rawPlayer : server.getConfigurationManager().playerEntityList) {
            inventory.capture((EntityPlayerMP) rawPlayer, trigger);
        }
    }
}
