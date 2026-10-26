package com.gtnhspeedrun.audit.api;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.LongSupplier;

import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * The API for other mods: copies of audit log events, and the IGT clock of the run. Check {@link #getApiVersion} before
 * the first call. The version goes up only on a change that breaks an existing caller.
 */
public final class AuditEvents {

    // javac copies a public int constant into each class that reads the constant, so another mod would see the version
    // that the mod was compiled against. Keep the constant private
    private static final int API_VERSION = 1;

    // The fields below are static. A subscription outlives every session, and publish() has no session object
    private static final List<Subscription> SUBSCRIPTIONS = new CopyOnWriteArrayList<>();
    // the union of the types of every subscription. publish() reads the union for every logged event
    private static volatile Set<String> subscribedTypes = Set.of();
    // the union of the types of every subscription with history. A session start reads the union once
    private static volatile Set<String> historyTypes = Set.of();
    // the clock of the live session, or null between worlds
    private static volatile @Nullable LongSupplier igtClock;
    // static: the log of the latest session, kept between worlds
    private static volatile @Nullable Logger log;

    private AuditEvents() {}

    /**
     * Send copies of the events of {@code types} to {@code listener}, in every session from now on. An event type is
     * the {@code type} field of a log line, such as {@code dim_first_visit}.
     */
    public static void subscribe(Set<String> types, AuditEventListener listener) {
        addSubscription(new Subscription(Set.copyOf(types), listener, false));
    }

    /**
     * Send copies of the events of {@code types} to {@code listener}, in every session from now on, as
     * {@link #subscribe} does. At the start of each session, the listener first gets the events of {@code types} from
     * the earlier sessions of the world, in log order, before any event of the session. An event that a backup restore
     * or a crash took out of the world is left out.
     */
    public static void subscribeWithHistory(Set<String> types, AuditEventListener listener) {
        addSubscription(new Subscription(Set.copyOf(types), listener, true));
    }

    private static synchronized void addSubscription(Subscription subscription) {
        SUBSCRIPTIONS.add(subscription);
        subscribedTypes = unionTypes(subscribedTypes, subscription.types());
        if (subscription.withHistory()) {
            historyTypes = unionTypes(historyTypes, subscription.types());
        }
    }

    private static Set<String> unionTypes(Set<String> types, Set<String> moreTypes) {
        final Set<String> union = new HashSet<>(types);
        union.addAll(moreTypes);
        return Set.copyOf(union);
    }

    public static int getApiVersion() {
        return API_VERSION;
    }

    /** Return the IGT of the run in ticks. The value is 0 between worlds and before timing starts. */
    public static long readIgtTicks() {
        final LongSupplier clock = igtClock;
        return clock == null ? 0 : clock.getAsLong();
    }

    /** Set the clock of the live session, or pass a null clock between worlds. Call from the audit mod only. */
    public static void attachSession(@Nullable LongSupplier sessionIgtClock, Logger sessionLog) {
        igtClock = sessionIgtClock;
        log = sessionLog;
    }

    /** Return the event types that a session start reads from the log for {@link #publishHistory}. */
    public static Set<String> readHistoryTypes() {
        return historyTypes;
    }

    /** Send a copy of one logged event to each listener of {@code type}. Call from the audit mod only. */
    public static void publish(String type, JsonObject data, long igtTicks) {
        if (subscribedTypes.contains(type)) {
            sendToListeners(new LoggedEvent(type, data, igtTicks), false);
        }
    }

    /**
     * Send a copy of one event of an earlier session to each listener of {@code type} with history. Call from the audit
     * mod only.
     */
    public static void publishHistory(String type, JsonObject data, long igtTicks) {
        if (historyTypes.contains(type)) {
            sendToListeners(new LoggedEvent(type, data, igtTicks), true);
        }
    }

    /**
     * Send a copy of {@code event} to each listener of the event's type, or with {@code historyOnly}, to each such
     * listener with history. A listener that throws is logged, and the next listener still gets the event.
     */
    private static void sendToListeners(LoggedEvent event, boolean historyOnly) {
        for (Subscription subscription : SUBSCRIPTIONS) {
            if (!(subscription.types()
                .contains(event.type()) && (subscription.withHistory() || !historyOnly))) {
                continue;
            }
            try {
                subscription.listener()
                    .onEvent(event.type(), copyObject(event.data()), event.igtTicks());
            } catch (RuntimeException | LinkageError failure) { // noqa: NAR142, degrade and report over another mod
                // a listener that uses a class of a mod that isn't installed throws NoClassDefFoundError
                final Logger sessionLog = log;
                if (sessionLog != null) {
                    sessionLog.warn(
                        "api.listener_failed type={} listener={}",
                        event.type(),
                        subscription.listener()
                            .getClass()
                            .getName(),
                        failure);
                }
            }
        }
    }

    // the Gson 2.2.4 of Minecraft has no public deepCopy()
    private static JsonObject copyObject(JsonObject source) {
        final JsonObject copy = new JsonObject();
        for (Map.Entry<String, JsonElement> entry : source.entrySet()) {
            copy.add(entry.getKey(), copyElement(entry.getValue()));
        }
        return copy;
    }

    private static JsonElement copyElement(JsonElement source) {
        if (source.isJsonObject()) {
            return copyObject(source.getAsJsonObject());
        }
        if (source.isJsonArray()) {
            final JsonArray copy = new JsonArray();
            for (JsonElement element : source.getAsJsonArray()) {
                copy.add(copyElement(element));
            }
            return copy;
        }
        // JsonPrimitive and JsonNull have no mutators
        return source;
    }

    private record Subscription(Set<String> types, AuditEventListener listener, boolean withHistory) {}

    private record LoggedEvent(String type, JsonObject data, long igtTicks) {}
}
