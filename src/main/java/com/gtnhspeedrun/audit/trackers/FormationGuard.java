package com.gtnhspeedrun.audit.trackers;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.gtnhspeedrun.audit.GtnhSpeedrunAudit;

/**
 * Runs a controller's formation logging inside its structure-check tick. Reflection over hundreds of addon
 * controller classes can fail in ways no test pack shows (v0.8.0 crashed servers on the Extreme Entity Crusher), so
 * a failure costs that formation's ledger line, never the server tick. Each controller class is reported once.
 */
public final class FormationGuard {

    private static final Set<String> REPORTED = ConcurrentHashMap.newKeySet();

    private FormationGuard() {}

    /** The mixins' MTEMultiBlockBase and TTMultiblockBase controllers, which implement GtFormationLogger. */
    public static void log(Object controller, boolean wasFormed) {
        final GtFormationLogger logger = (GtFormationLogger) controller;
        try {
            if (wasFormed) {
                logger.audit$logFootprintChange();
            } else {
                logger.audit$logFormation();
            }
        } catch (RuntimeException | LinkageError e) {
            final String type = controller.getClass()
                .getName();
            if (REPORTED.add(type)) {
                GtnhSpeedrunAudit.LOG.error("Multiblock formation NOT audited for {}", type, e);
            }
        }
    }
}
