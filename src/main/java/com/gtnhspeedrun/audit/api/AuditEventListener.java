package com.gtnhspeedrun.audit.api;

import com.google.gson.JsonObject;

/** Receive audit log events in another mod. Register one through {@link AuditEvents#subscribe}. */
@FunctionalInterface
public interface AuditEventListener {

    /**
     * Receive one event. The call runs on the thread that logged the event: the server thread, the RCON thread or a
     * netty thread. Queue the event and return. NEVER read or change world state here.
     *
     * @param data a copy of the logged data. An edit to the copy changes nothing in the log
     */
    void onEvent(String type, JsonObject data, long igtTicks);
}
