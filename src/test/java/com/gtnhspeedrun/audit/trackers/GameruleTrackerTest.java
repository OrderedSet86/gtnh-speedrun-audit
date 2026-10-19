package com.gtnhspeedrun.audit.trackers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.TreeMap;

import org.junit.jupiter.api.Test;

/** Test the rule-set diff behind gamerule_change: changed values, and rules that appear or vanish. */
class GameruleTrackerTest {

    @Test
    void unchangedRulesDiffEmpty() {
        assertTrue(
            GameruleTracker.diff(rules("keepInventory", "false"), rules("keepInventory", "false"))
                .isEmpty());
    }

    @Test
    void changedValueReportsNewValue() {
        final Map<String, String> diff = GameruleTracker.diff(
            rules("doMobSpawning", "true", "keepInventory", "false"),
            rules("doMobSpawning", "false", "keepInventory", "false"));
        assertEquals(rules("doMobSpawning", "false"), diff);
    }

    @Test
    void addedRuleReportsItsValueAndRemovedRuleReportsNull() {
        final Map<String, String> diff = GameruleTracker.diff(rules("a", "1"), rules("b", "2"));
        assertEquals(2, diff.size());
        assertEquals("2", diff.get("b"));
        assertTrue(diff.containsKey("a"));
        assertNull(diff.get("a"));
    }

    private static Map<String, String> rules(String... keysAndValues) {
        final Map<String, String> rules = new TreeMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            rules.put(keysAndValues[i], keysAndValues[i + 1]);
        }
        return rules;
    }
}
