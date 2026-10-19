package com.gtnhspeedrun.audit.trackers;

import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChatStyle;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.StatCollector;

import org.jetbrains.annotations.Nullable;

import com.google.gson.JsonObject;
import com.gtnhspeedrun.audit.GtnhSpeedrunAudit;
import com.gtnhspeedrun.audit.core.SessionManager;

/**
 * Log the multiblock formations and footprint changes that the multiblock mixins report.
 *
 * <p>
 * Signatures use only vanilla, JDK and audit types. A mixin computes any modded-API values before the call, so this
 * class loads on a pack without some or all of the target mods. Every log method returns early when no session is
 * logging.
 */
public final class MultiblockSinks {

    private MultiblockSinks() {}

    /** Log a formation with no hatch information, such as a Railcraft multiblock or the bricked blast furnace. */
    public static void logMultiblockFormed(String kind, MachineSite site) {
        final SessionManager session = GtnhSpeedrunAudit.session();
        if (!(session != null && session.isFirstTime("mb:" + site.formatKey()))) {
            return;
        }
        session.logger()
            .log("multiblock_formed", describeFormation(session, kind, site));
    }

    /**
     * Log a formation once per session for each class, position and {@link MultiblockPower#dedupeSuffix}.
     *
     * <p>
     * A multiblock forms again each time its chunk reloads, because the MTE is recreated unformed and checks its
     * structure again. With this key, the formation is logged again after a swap of LV energy hatches for UV energy
     * hatches, and not after a chunk reload. After a server restart, every formed multiblock is logged again.
     * {@link #logGtMultiblockFormed} dedupes GT multiblocks across restarts. The {@code firstOfClass} flag comes from
     * the anchor, so the flag spans sessions and rewinds with the world.
     */
    public static void logMultiblockFormed(String kind, MachineSite site, MultiblockPower power) {
        final SessionManager session = GtnhSpeedrunAudit.session();
        if (!(session != null && session.isFirstTime("mb:" + site.formatKey() + power.dedupeSuffix()))) {
            return;
        }
        final JsonObject data = describeFormation(session, kind, site);
        power.writeTo(data);
        session.logger()
            .log("multiblock_formed", data);
    }

    /**
     * Log a GT multiblock formation, deduped by a marker that the controller saves in its NBT. The formation is logged
     * once, and again only when the hatch tiers, the amperages, or the coil or structure tiers change.
     *
     * <p>
     * A server restart or a chunk reload loads the marker back, so no formation is logged at the next check. A
     * restored backup restores the earlier marker, so a formation that the backup undid is logged again. The footprint
     * is on the line and not in the marker, so no formation is logged again for a footprint change alone.
     *
     * @param loggedMarker the controller's marker, null when the controller has never logged a formation
     * @return the marker to save, unchanged when nothing was logged
     */
    public static String logGtMultiblockFormed(String loggedMarker, GtFormation formation) {
        final SessionManager session = GtnhSpeedrunAudit.session();
        final String marker = formation.power()
            .dedupeSuffix();
        if (session == null || marker.equals(loggedMarker)) {
            return loggedMarker;
        }
        final JsonObject data = describeFormation(session, "gt", formation.site());
        formation.power()
            .writeTo(data);
        if (formation.footprint() != null) {
            formation.footprint()
                .writeTo(data);
        }
        session.logger()
            .log("multiblock_formed", data);
        return marker;
    }

    /**
     * Mark the class in the anchor's {@code formedMultiblocks} set, and return the start of the
     * {@code multiblock_formed} line.
     */
    private static JsonObject describeFormation(SessionManager session, String kind, MachineSite site) {
        final boolean firstOfClass = session.anchor().formedMultiblocks.add(site.className());
        if (firstOfClass) {
            session.anchor()
                .markDirty();
        }
        final JsonObject data = new JsonObject();
        data.addProperty("kind", kind);
        site.writeTo(data);
        data.addProperty("firstOfClass", firstOfClass);
        return data;
    }

    /**
     * Log the footprint of a formed GT multiblock whose footprint changed with no new formation. Examples are a wrench
     * rotation, an LSC that grew layers, and the first check after the mod version that added footprints.
     *
     * @return true when a line was logged
     */
    public static boolean logGtMultiblockFootprint(MachineSite site, MultiblockFootprint footprint) {
        final SessionManager session = GtnhSpeedrunAudit.session();
        if (session == null) {
            return false;
        }
        final JsonObject data = new JsonObject();
        site.writeTo(data);
        footprint.writeTo(data);
        session.logger()
            .log("multiblock_footprint", data);
        return true;
    }

    // The server resolves the chat text, because a client may have no lang file for this server-only mod. Such a
    // client shows a translation component as its raw key. The Forge 1614 dedicated server loads
    // assets/gtnhspeedrunaudit/lang/en_US.lang into StringTranslate.

    /**
     * Report a multiblock formation or footprint change that this mod failed to log. A missing
     * {@code multiblock_formed} line reads as a multiblock that never formed. The failure is therefore logged once per
     * controller per session, and sent in red chat to every player once per controller class per session. The line has
     * no local name.
     *
     * @param what "formation" or "footprint"
     */
    public static void logMultiblockLogFailed(String what, MachineSite site, Throwable error) {
        final SessionManager session = GtnhSpeedrunAudit.session();
        if (session == null || !session.isFirstTime("mbfail:" + site.formatKey())) {
            return;
        }
        final JsonObject data = new JsonObject();
        data.addProperty("what", what);
        data.addProperty("class", site.className());
        site.writePositionTo(data);
        data.addProperty("error", String.valueOf(error));
        session.logger()
            .log("multiblock_log_failed", data);
        final MinecraftServer server = MinecraftServer.getServer();
        if (server == null || !session.isFirstTime("mbfail-chat:" + site.className())) {
            return;
        }
        final String key = "formation".equals(what) ? "gtnhspeedrunaudit.multiblock.formingNotRecorded"
            : "gtnhspeedrunaudit.multiblock.footprintNotRecorded";
        server.getConfigurationManager()
            .sendChatMsg(
                new ChatComponentText(
                    StatCollector.translateToLocalFormatted(
                        key,
                        site.className(),
                        site.dim(),
                        site.x(),
                        site.y(),
                        site.z(),
                        error)).setChatStyle(new ChatStyle().setColor(EnumChatFormatting.RED)));
    }

    /**
     * The site, power and footprint of a GT multiblock at formation.
     *
     * @param footprint null when unknown
     */
    public record GtFormation(MachineSite site, MultiblockPower power, @Nullable MultiblockFootprint footprint) {}
}
