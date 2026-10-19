package com.gtnhspeedrun.audit.trackers;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.gtnhspeedrun.audit.GtnhSpeedrunAudit;

/**
 * Run a controller's formation logging inside its structure-check tick, so a failure loses that formation's ledger
 * line and never the server tick.
 *
 * <p>
 * Reflection over hundreds of addon controller classes can fail in ways that never happened in a test pack. A failure
 * is logged and announced through {@link AuditSinks#logMultiblockLogFailed}. The stack trace goes to the server log
 * once per controller class.
 */
public final class FormationGuard {

    // static: the mixins call log() with no instance. A controller class is reported once per JVM
    private static final Set<String> REPORTED = ConcurrentHashMap.newKeySet();

    private FormationGuard() {}

    /**
     * Call {@code audit$logFootprintChange()} when the multiblock was formed before the check, and
     * {@code audit$logFormation()} otherwise. No RuntimeException or LinkageError from the logging reaches the caller.
     *
     * @param site the controller's site for the failure line
     */
    public static void log(GtFormationLogger controller, boolean wasFormed, MachineSite site) {
        try {
            if (wasFormed) {
                controller.audit$logFootprintChange();
            } else {
                controller.audit$logFormation();
            }
        } catch (RuntimeException | LinkageError e) { // noqa: NAR142, reflection over GT addon controller classes
            final String type = controller.getClass()
                .getName();
            if (REPORTED.add(type)) {
                GtnhSpeedrunAudit.LOG.error("Multiblock formation NOT audited for {}", type, e);
            }
            AuditSinks.logMultiblockLogFailed(wasFormed ? "footprint" : "formation", site, e);
        }
    }
}
