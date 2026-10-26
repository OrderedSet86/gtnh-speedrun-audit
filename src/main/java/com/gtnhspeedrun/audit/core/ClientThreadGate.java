package com.gtnhspeedrun.audit.core;

import org.jetbrains.annotations.Nullable;

/**
 * Name the thread whose audit lines are dropped: the client thread of a singleplayer game.
 *
 * <p>
 * In singleplayer the client thread runs machine code in a fake world, the multiblock structure preview in dim
 * 2147483647. That world is not remote, so the GT and Railcraft formation checks run as on a server, and the audit
 * mixins log them. Those lines are not events of the run's world. The class of a preview formation is also added to the
 * anchor, and the run's first real formation of the class is then logged with {@code firstOfClass} false.
 */
public final class ClientThreadGate {

    /** The gate of a dedicated server. A dedicated server has no client thread. */
    public static final ClientThreadGate NONE = new ClientThreadGate(null);

    private final @Nullable Thread clientThread;

    public ClientThreadGate(@Nullable Thread clientThread) {
        this.clientThread = clientThread;
    }

    public boolean isGated(Thread thread) {
        return clientThread != null && thread == clientThread;
    }
}
