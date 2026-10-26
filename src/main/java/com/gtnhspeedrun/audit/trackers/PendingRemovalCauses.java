package com.gtnhspeedrun.audit.trackers;

import java.util.HashMap;
import java.util.Map;

import org.jetbrains.annotations.Nullable;

/**
 * Keep the removal causes noted in this tick, by block position, until {@link #takeCause} returns the cause. A later
 * note for a position replaces the earlier note.
 */
public final class PendingRemovalCauses {

    private final Map<BlockSpot, RemovalCause> causes = new HashMap<>();

    public void note(BlockSpot spot, RemovalCause cause) {
        causes.put(spot, cause);
    }

    /** Return and forget the cause noted for {@code spot}, or return null when none is noted. */
    public @Nullable RemovalCause takeCause(BlockSpot spot) {
        return causes.remove(spot);
    }

    public void clear() {
        causes.clear();
    }
}
