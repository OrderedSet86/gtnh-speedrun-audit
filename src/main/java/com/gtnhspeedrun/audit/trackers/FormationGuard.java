package com.gtnhspeedrun.audit.trackers;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.gtnhspeedrun.audit.GtnhSpeedrunAudit;

/**
 * Runs a controller's formation logging inside its structure-check tick. Reflection over hundreds of addon
 * controller classes can fail in ways no test pack shows (v0.8.0 crashed servers on the Extreme Entity Crusher), so
 * a failure costs that formation's ledger line, never the server tick. The gap is logged and announced through
 * {@link AuditSinks#multiblockLogFailed}; the stack trace goes to the server log once per controller class.
 */
public final class FormationGuard {

    private static final Set<String> REPORTED = ConcurrentHashMap.newKeySet();

    private FormationGuard() {}

    /** The mixins' MTEMultiBlockBase and TTMultiblockBase controllers, which implement GtFormationLogger. */
    public static void log(GtFormationLogger controller, boolean wasFormed, int dim, int x, int y, int z) {
        try {
            if (wasFormed) {
                controller.audit$logFootprintChange();
            } else {
                controller.audit$logFormation();
            }
        } catch (RuntimeException | LinkageError e) {
            final String type = controller.getClass()
                .getName();
            if (REPORTED.add(type)) {
                GtnhSpeedrunAudit.LOG.error("Multiblock formation NOT audited for {}", type, e);
            }
            AuditSinks.multiblockLogFailed(
                wasFormed ? "footprint" : "formation",
                controller.getClass()
                    .getSimpleName(),
                dim,
                x,
                y,
                z,
                e);
        }
    }
}
