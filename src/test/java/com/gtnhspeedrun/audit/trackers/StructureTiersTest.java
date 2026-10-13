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
        // the DEFC's casings and the steam multis' parts put "tier" first or in the middle
        assertTrue(StructureTiers.isTierName("mTierCasing"));
        assertTrue(StructureTiers.isTierName("mFusionTierCasing"));
        assertTrue(StructureTiers.isTierName("tierGearBoxCasing"));
        assertTrue(StructureTiers.isTierName("tTier"));
        // lookups, settings and counts change without the structure changing
        assertFalse(StructureTiers.isTierName("lastCheckedTierIndex"));
        assertFalse(StructureTiers.isTierName("_mRadiusTierOverride"));
        assertFalse(StructureTiers.isTierName("tMinMotorTier"));
        assertFalse(StructureTiers.isTierName("notATierCount"));
        assertFalse(StructureTiers.isTierName("frontier"));
    }

    @SuppressWarnings("unused")
    static class MTEEyeOfHarmony {

        private int spacetimeCompressionFieldMetadata = 8;
        private int timeAccelerationFieldMetadata = 7;
        private long parallelAmount = 1;
    }

    @Test
    void readsMetadataFieldTiers() {
        final Map<String, String> tiers = StructureTiers.read(new MTEEyeOfHarmony());
        assertEquals("8", tiers.get("spacetimeCompressionFieldMetadata"));
        assertEquals("7", tiers.get("timeAccelerationFieldMetadata"));
        assertFalse(tiers.containsKey("parallelAmount"));
    }

    enum FoundryModule {
        UNSET,
        HELIOCAST
    }

    static class FoundryData {

        public FoundryModule[] modules = { FoundryModule.HELIOCAST, FoundryModule.UNSET };
        public int tier = 3;
    }

    @SuppressWarnings("unused")
    static class MTEExoFoundry {

        public final FoundryData foundryData = new FoundryData();
    }

    @SuppressWarnings("unused")
    static class MTEPlasmaForge {

        private boolean convergence = true;
    }

    @Test
    void readsListedPaths() {
        final Map<String, String> foundry = StructureTiers.read(new MTEExoFoundry());
        assertEquals("3", foundry.get("foundryData.tier"));
        assertEquals("[HELIOCAST,UNSET]", foundry.get("foundryData.modules"));
        assertEquals(
            "true",
            StructureTiers.read(new MTEPlasmaForge())
                .get("convergence"));
    }
}
