package com.gtnhspeedrun.audit.trackers;

import java.util.function.Consumer;
import java.util.function.Supplier;

import com.google.gson.JsonObject;

/**
 * Read the state of the backup mod for the world-saving lines. The @Mod class builds the probe from the ServerUtilities
 * containment class, so the trackers package doesn't depend on compat.
 *
 * @param isRunning      returns whether a backup is running, or null when the backup state is unknown
 * @param describeNewest adds the fields of the newest backup to a line
 */
public record BackupProbe(Supplier<Boolean> isRunning, Consumer<JsonObject> describeNewest) {}
