package com.gtnhspeedrun.audit.trackers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.TreeMap;

import org.junit.jupiter.api.Test;

/** The rule-set diff behind gamerule_change: changed values, plus rules that appear or vanish. */
class GameruleTrackerTest {

    private static Map<String, String> rules(String... kv) {
        final Map<String, String> m = new TreeMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    @Test
    void unchangedRulesDiffEmpty() {
        assertTrue(
            GameruleTracker.diff(rules("keepInventory", "false"), rules("keepInventory", "false"))
                .isEmpty());
    }

    @Test
    void changedValueReportsNewValue() {
        final Map<String, String> d = GameruleTracker.diff(
            rules("doMobSpawning", "true", "keepInventory", "false"),
            rules("doMobSpawning", "false", "keepInventory", "false"));
        assertEquals(rules("doMobSpawning", "false"), d);
    }

    @Test
    void addedAndRemovedRules() {
        final Map<String, String> d = GameruleTracker.diff(rules("a", "1"), rules("b", "2"));
        assertEquals(2, d.size());
        assertEquals("2", d.get("b"));
        assertTrue(d.containsKey("a"));
        assertEquals(null, d.get("a"));
    }
}
