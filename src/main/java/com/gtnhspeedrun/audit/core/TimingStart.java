package com.gtnhspeedrun.audit.core;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;

import com.google.gson.JsonObject;

/** Start the run clocks of a session at the first horizontal movement of any player. */
public final class TimingStart {

    private final MinecraftServer server;
    private final ChainAnchorData anchor;
    private final AuditLogger logger;
    private final Map<UUID, double[]> preTimingPositions = new HashMap<>();

    public TimingStart(MinecraftServer server, ChainAnchorData anchor, AuditLogger logger) {
        this.server = server;
        this.anchor = anchor;
        this.logger = logger;
    }

    /**
     * Start timing at the first horizontal movement of any player, and return true in the call that started timing.
     * The server receives no key presses, so horizontal movement stands in for the first WASD press. One tick of
     * walking moves about 0.21 blocks. A threshold of 0.03 blocks is above server jitter and below a single step.
     * Vertical movement does not count, so a drop from a spawn platform does not start the timer.
     */
    public boolean checkFirstMovement() {
        // 0.03 blocks, squared
        final double MIN_STEP_SQUARED = 0.0009;
        for (Object entry : server.getConfigurationManager().playerEntityList) {
            final EntityPlayerMP player = (EntityPlayerMP) entry;
            final UUID id = player.getGameProfile()
                .getId();
            final double[] prev = preTimingPositions.get(id);
            if (prev == null) {
                preTimingPositions.put(id, new double[] { player.posX, player.posZ });
                continue;
            }
            final double dx = player.posX - prev[0];
            final double dz = player.posZ - prev[1];
            prev[0] = player.posX;
            prev[1] = player.posZ;
            if (dx * dx + dz * dz > MIN_STEP_SQUARED) {
                anchor.timingStarted = true;
                anchor.timingStartWallMs = System.currentTimeMillis();
                preTimingPositions.clear();
                final JsonObject data = new JsonObject();
                data.addProperty("uuid", id.toString());
                data.addProperty("name", player.getCommandSenderName());
                data.addProperty("dim", player.dimension);
                logger.log("timing_started", data);
                return true;
            }
        }
        return false;
    }
}
