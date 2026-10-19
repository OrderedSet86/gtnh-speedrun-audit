package com.gtnhspeedrun.audit.selftest;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.gtnhspeedrun.audit.GtnhSpeedrunAudit;

/** Record the self-test's check results in run order, and log each one. */
public final class CheckList {

    private final List<Check> checks = new ArrayList<>();

    public void check(String name, boolean ok, String detail) {
        checks.add(new Check(name, ok, detail == null ? "" : detail));
        final String detailSuffix = detail == null ? "" : " (" + detail + ")";
        GtnhSpeedrunAudit.LOG.info("[selftest] {} — {}{}", ok ? "ok  " : "FAIL", name, detailSuffix);
    }

    public List<Check> getResults() {
        return Collections.unmodifiableList(checks);
    }

    /** One check result. {@code detail} is empty when the check has no detail. */
    public record Check(String name, boolean ok, String detail) {}
}
