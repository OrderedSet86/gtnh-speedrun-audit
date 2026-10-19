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

/** Test allowlist matching, watch-list matching and the hash of the item ID table. */
class SmallMatchersTest {

    @Test
    void commandOutputAllowlistMatchesCommandOrCommandAndFirstArg() {
        final Set<String> allow = new HashSet<>(Arrays.asList("gamerule", "gt global_energy_display"));
        assertTrue(CommandOutputCapture.isAllowed(allow, "gamerule", "dofiretick"));
        assertTrue(CommandOutputCapture.isAllowed(allow, "gt", "global_energy_display"));
        assertFalse(CommandOutputCapture.isAllowed(allow, "gt", "global_energy_add"));
        assertFalse(CommandOutputCapture.isAllowed(allow, "msg", "bob"));
        assertFalse(CommandOutputCapture.isAllowed(allow, "gt", null));
    }

    @Test
    void watchedBlockMatchesByNameOrByNameAndMeta() {
        final Set<String> watched = new HashSet<>(Arrays.asList("Thaumcraft:blockJar@2", "AWWayofTime:Altar"));
        assertTrue(MachinePlacementTracker.isWatched(watched, "Thaumcraft:blockJar", 2));
        assertFalse(MachinePlacementTracker.isWatched(watched, "Thaumcraft:blockJar", 0));
        assertTrue(MachinePlacementTracker.isWatched(watched, "AWWayofTime:Altar", 0));
        assertFalse(MachinePlacementTracker.isWatched(new HashSet<>(), "AWWayofTime:Altar", 0));
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
