package com.gtnhspeedrun.audit.core;

/** Classify a server start for the verifyVerdict of session_start. */
public final class StartupVerdict {

    private StartupVerdict() {}

    /**
     * Return the startup verdict for session_start from the log tail and the anchor alone. {@link Verifier} walks
     * every line.
     */
    public static String classify(ChainTail tail, boolean previousCrashed, SavedAnchor anchor) {
        if (tail.seq() < 0) {
            return anchor.lastSeq() < 0 ? "NEW_WORLD" : "LOG_MISSING";
        }
        if (anchor.lastSeq() < tail.seq()) {
            // The world is behind the log. The gap is a crash only if the log has no clean stop, and the world was
            // last saved mid-session by the session that crashed. SessionManager.start() saves the anchor at once. A
            // restore of a save from any earlier session, or of a save made at a clean stop, fails this check.
            final boolean crashedSessionSavedLast = !anchor.cleanShutdown() && !anchor.sessionId()
                .isEmpty()
                && anchor.sessionId()
                    .equals(tail.sid());
            if (previousCrashed && crashedSessionSavedLast) {
                return "CRASH_RECOVERY";
            }
            return anchor.lastSeq() < 0 ? "ANCHOR_MISSING" : "WORLD_ROLLBACK";
        }
        if (anchor.lastSeq() > tail.seq()) {
            return "LOG_TRUNCATED";
        }
        return anchor.lastHash()
            .equals(tail.hash()) ? "OK" : "TAMPERED_TAIL";
    }

    /** Return whether {@code verdict} means the world is a save from before the end of the log. */
    public static boolean isWorldRewound(String verdict) {
        return "WORLD_ROLLBACK".equals(verdict) || "CRASH_RECOVERY".equals(verdict);
    }

    /** The anchor fields that classify() reads, with the values loaded from the world before this session starts. */
    public record SavedAnchor(long lastSeq, String lastHash, boolean cleanShutdown, String sessionId) {

        public static SavedAnchor readFrom(ChainAnchorData anchor) {
            return new SavedAnchor(anchor.lastSeq, anchor.lastHash, anchor.cleanShutdown, anchor.sessionId);
        }
    }
}
