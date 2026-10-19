package com.gtnhspeedrun.audit.snapshot;

import java.util.List;

import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChatStyle;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.StatCollector;

import org.jetbrains.annotations.Nullable;

import com.google.gson.JsonObject;
import com.gtnhspeedrun.audit.GtnhSpeedrunAudit;
import com.gtnhspeedrun.audit.core.SessionManager;

/** Log and announce the grids that the AE2 census could not read. */
public final class Ae2CensusFailures {

    private Ae2CensusFailures() {}

    /**
     * Log a grid, or the whole grid list, that the AE2 census could not read. The method uses no AE2 types, so the
     * self-test calls the method on a server without AE2.
     *
     * @return a one-line description for {@link #announceAe2CensusFailure}
     */
    public static String logAe2CensusFailure(Ae2CensusFailure failure) {
        final String gridId = failure.gridId();
        GtnhSpeedrunAudit.LOG.error(
            "AE2 census {} failed ({}{})",
            failure.census(),
            failure.where(),
            gridId == null ? "" : " " + gridId,
            failure.error());
        final SessionManager session = GtnhSpeedrunAudit.session();
        if (session != null) {
            final JsonObject data = new JsonObject();
            data.addProperty("census", failure.census());
            data.addProperty("where", failure.where());
            if (gridId != null) {
                data.addProperty("gridId", gridId);
            }
            data.addProperty("error", String.valueOf(failure.error()));
            session.logger()
                .log("ae2_census_failed", data);
        }
        return (gridId == null ? failure.where() : failure.where() + " " + gridId) + ": " + failure.error();
    }

    /**
     * Send a red chat line to every player, in the style of the ServerUtilities message for a failed backup. The line
     * is about a failure of this mod's record of the network, so no player reads the line as a failure of AE2.
     */
    public static void announceAe2CensusFailure(List<String> failures) {
        final MinecraftServer server = MinecraftServer.getServer();
        if (server == null || failures.isEmpty()) {
            return;
        }
        final String detail = failures.size() == 1 ? failures.get(0)
            : StatCollector
                .translateToLocalFormatted("gtnhspeedrunaudit.ae2.censusFailures", failures.size(), failures.get(0));
        server.getConfigurationManager()
            .sendChatMsg(
                new ChatComponentText(
                    StatCollector.translateToLocalFormatted("gtnhspeedrunaudit.ae2.censusNotRecorded", detail))
                        .setChatStyle(new ChatStyle().setColor(EnumChatFormatting.RED)));
    }

    /**
     * One failed read of an AE2 census.
     *
     * @param census the session's census number
     * @param where  {@code grid list} or {@code grid}
     * @param gridId null for the grid list, or for a grid whose id read failed
     */
    public record Ae2CensusFailure(int census, String where, @Nullable String gridId, Throwable error) {}
}
