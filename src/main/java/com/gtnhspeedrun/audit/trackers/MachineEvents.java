package com.gtnhspeedrun.audit.trackers;

import com.google.gson.JsonObject;
import com.gtnhspeedrun.audit.GtnhSpeedrunAudit;
import com.gtnhspeedrun.audit.core.SessionManager;

/**
 * Log the GT machine explosions, the GT power failures and the Blood Altar tier changes that the machine mixins report.
 *
 * <p>
 * Signatures use only vanilla, JDK and audit types. A mixin computes any modded-API values before the call, so this
 * class loads on a pack without GT or Blood Magic. Every log method returns early when no session is logging.
 */
public final class MachineEvents {

    private MachineEvents() {}

    /** Log a GT machine explosion. A backup restore usually follows an explosion. */
    public static void logGtExplosion(MachineSite site, long power) {
        final SessionManager session = GtnhSpeedrunAudit.session();
        if (session == null) {
            return;
        }
        final JsonObject data = new JsonObject();
        site.writeTo(data);
        data.addProperty("power", power);
        session.logger()
            .log("gt_explosion", data);
    }

    /** Log a multiblock that stopped for lack of power, the event that GT's /powerfails lists. */
    public static void logGtPowerfail(MachineSite site) {
        final SessionManager session = GtnhSpeedrunAudit.session();
        if (session == null) {
            return;
        }
        final JsonObject data = new JsonObject();
        site.writeTo(data);
        session.logger()
            .log("gt_powerfail", data);
    }

    /**
     * Log a Blood Altar tier change after the altar checked the runes. In {@code upgradeLevel}, 0 is unchecked and 1
     * to 6 are tiers I to VI. The line has the altar's position and no class name.
     */
    public static void logBloodAltarTier(MachineSite site, int from, int to) {
        final SessionManager session = GtnhSpeedrunAudit.session();
        if (session == null) {
            return;
        }
        final JsonObject data = new JsonObject();
        site.writePositionTo(data);
        data.addProperty("from", from);
        data.addProperty("to", to);
        session.logger()
            .log("blood_altar_tier", data);
    }
}
