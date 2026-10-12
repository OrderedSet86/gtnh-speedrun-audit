package com.gtnhspeedrun.audit.trackers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

/** Which controller fields StructureTiers reads, on stand-in classes shaped like GT controllers. */
class StructureTiersTest {

    enum HeatingCoilLevel {
        LV,
        HV
    }

    static class Base {

        int baseTier = 9; // stands in for shared plumbing; read because Base is not a GT base class
    }

    @SuppressWarnings("unused")
    static class Controller extends Base {

        private HeatingCoilLevel mCoilLevel = HeatingCoilLevel.HV;
        private int mHeatingCapacity = 4500;
        private int glassTier = 6;
        private int casingTier = 3;
        private int renderSawbladeTier = 2;
        private int maxTier = 14;
        private int currentRecipeTier = 1;
        private long tempTier = 5;
        private static int staticTier = 7;
        private int notATierCount = 1;
    }

    @Test
    void readsCoilHeatAndTierFields() {
        final Map<String, String> tiers = StructureTiers.read(new Controller());
        assertEquals("HV", tiers.get("mCoilLevel"));
        assertEquals("4500", tiers.get("mHeatingCapacity"));
        assertEquals("6", tiers.get("glassTier"));
        assertEquals("3", tiers.get("casingTier"));
        assertEquals("9", tiers.get("baseTier"));
    }

    @Test
    void skipsBookkeepingStaticAndWideFields() {
        final Map<String, String> tiers = StructureTiers.read(new Controller());
        assertFalse(tiers.containsKey("renderSawbladeTier"));
        assertFalse(tiers.containsKey("maxTier"));
        assertFalse(tiers.containsKey("currentRecipeTier"));
        assertFalse(tiers.containsKey("tempTier"), "long tier fields are not structure tiers");
        assertFalse(tiers.containsKey("staticTier"));
        assertFalse(tiers.containsKey("notATierCount"));
    }

    @Test
    void stopsAtGtBaseClasses() {
        assertTrue(StructureTiers.isSharedBase(Object.class));
        assertFalse(StructureTiers.isSharedBase(Controller.class));
    }

    @Test
    void tierNames() {
        assertTrue(StructureTiers.isTierName("motorTier"));
        assertTrue(StructureTiers.isTierName("mTier"));
        assertFalse(StructureTiers.isTierName("requiredPCBTier"));
        assertFalse(StructureTiers.isTierName("tiers"));
    }
}
