package com.gtnhspeedrun.audit.harness;

import java.util.Collections;
import java.util.List;

import net.minecraft.entity.player.EntityPlayerMP;

import com.gtnhspeedrun.audit.compat.Compat;
import com.sinthoras.visualprospecting.VisualProspecting_API;
import com.sinthoras.visualprospecting.database.OreVeinPosition;

import detrav.net.DetravNetwork;
import detrav.net.ProspectingPacket;

/**
 * Send one Electric Prospector's Scanner map and the map's VisualProspecting results to a fake player, for the
 * self-test of the prospecting mixins. The GT and VisualProspecting classes exist only when both mods are installed, so
 * every reference to them is in the nested {@code Holder} class, behind the {@code Compat} guards.
 */
public final class ProspectDrill {

    public static final int RADIUS_CHUNKS = 2;
    public static final int MODE = 1;
    private static final int VEIN_SEARCH_BLOCKS = 64;

    private ProspectDrill() {}

    /**
     * Report a scan around {@code player}, with the VisualProspecting ore veins within {@link #VEIN_SEARCH_BLOCKS} of
     * the player.
     *
     * @return whether the drill ran: false without GregTech or VisualProspecting
     */
    public static boolean sendScan(EntityPlayerMP player) {
        if (!(Compat.GT5.isLoaded() && Compat.VISUAL_PROSPECTING.isLoaded())) {
            return false;
        }
        Holder.sendScan(player);
        return true;
    }

    private static final class Holder {

        private static void sendScan(EntityPlayerMP player) {
            final int x = (int) Math.floor(player.posX);
            final int z = (int) Math.floor(player.posZ);
            final List<OreVeinPosition> veins = VisualProspecting_API.LogicalServer
                .prospectOreVeinsWithinRadius(player.dimension, x, z, VEIN_SEARCH_BLOCKS);
            // a fake player has no netty channel, so FML's send to the player throws after the HEAD injections ran
            try {
                DetravNetwork.INSTANCE
                    .sendToPlayer(new ProspectingPacket(x >> 4, z >> 4, x, z, RADIUS_CHUNKS, MODE), player);
            } catch (RuntimeException ignored) { // noqa: NAR142, degrade over FML's send to a fake player
            }
            try {
                VisualProspecting_API.LogicalServer
                    .sendProspectionResultsToClient(player, veins, Collections.emptyList());
            } catch (RuntimeException ignored) { // noqa: NAR142, degrade over FML's send to a fake player
            }
        }
    }
}
