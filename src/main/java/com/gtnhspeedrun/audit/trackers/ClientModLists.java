package com.gtnhspeedrun.audit.trackers;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;

/** Keep the mod list that each client sends in the FML handshake, for the log line of the player's login. */
public final class ClientModLists {

    /**
     * The client handshake mod lists by connection. 1.7.10 FML logs the client's handshake mod list and then
     * discards the list, so the handshake mixin passes the list to {@link #storeClientModList}. The
     * {@code PlayerLoggedInEvent} handler reads the joining player's list. The keys are weak, so a dropped
     * connection's entry is removed once the connection is garbage-collected. The netty thread writes and the server
     * thread reads, so the map is synchronized.
     */
    private static final Map<Object, Map<String, String>> CLIENT_MODS = Collections
        .synchronizedMap(new WeakHashMap<>());

    private ClientModLists() {}

    public static void storeClientModList(Object dispatcher, Map<String, String> mods) {
        if (dispatcher != null && mods != null) {
            CLIENT_MODS.put(dispatcher, new HashMap<>(mods));
        }
    }

    /**
     * Return null when the connection had no FML mod handshake, such as an integrated-server local channel or a
     * vanilla client.
     */
    public static Map<String, String> readClientMods(Object dispatcher) {
        return dispatcher == null ? null : CLIENT_MODS.get(dispatcher);
    }
}
