package com.gtnhspeedrun.audit.trackers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.junit.jupiter.api.Test;

/** Allowlist, watch-list and ID-table hash matching. */
class SmallMatchersTest {

    @Test
    void commandOutputAllowlist() {
        final Set<String> allow = new HashSet<>(Arrays.asList("gamerule", "gt global_energy_display"));
        assertTrue(CommandOutputCapture.allowed(allow, "gamerule", "dofiretick"));
        assertTrue(CommandOutputCapture.allowed(allow, "gt", "global_energy_display"));
        assertFalse(CommandOutputCapture.allowed(allow, "gt", "global_energy_add"));
        assertFalse(CommandOutputCapture.allowed(allow, "msg", "bob"));
        assertFalse(CommandOutputCapture.allowed(allow, "gt", null));
    }

    @Test
    void watchedBlocksByNameOrMeta() {
        final Set<String> watched = new HashSet<>(Arrays.asList("Thaumcraft:blockJar@2", "AWWayofTime:Altar"));
        assertTrue(MachinePlacementTracker.watches(watched, "Thaumcraft:blockJar", 2));
        assertFalse(MachinePlacementTracker.watches(watched, "Thaumcraft:blockJar", 0));
        assertTrue(MachinePlacementTracker.watches(watched, "AWWayofTime:Altar", 0));
        assertFalse(MachinePlacementTracker.watches(new HashSet<>(), "AWWayofTime:Altar", 0));
    }

    @Test
    void idTableHashIsOrderStableAndValueSensitive() {
        final Map<String, Integer> a = new TreeMap<>();
        a.put("\u0002minecraft:stick", 280);
        a.put("\u0001minecraft:stone", 1);
        final Map<String, Integer> b = new TreeMap<>(a);
        assertEquals(ItemIdTable.hash(a), ItemIdTable.hash(b));
        b.put("\u0002minecraft:stick", 281);
        assertNotEquals(ItemIdTable.hash(a), ItemIdTable.hash(b));
    }
}
