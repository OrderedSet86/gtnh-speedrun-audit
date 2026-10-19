package com.gtnhspeedrun.audit.trackers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** Test which controller fields StructureTiers reads, on stand-in classes shaped like GT controllers. */
class StructureTiersTest {

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
    void tierNamesMatchStructureTiersButNotLookupsSettingsOrCounts() {
        assertTrue(StructureTiers.isTierName("motorTier"));
        assertTrue(StructureTiers.isTierName("mTier"));
        assertFalse(StructureTiers.isTierName("requiredPCBTier"));
        assertFalse(StructureTiers.isTierName("tiers"));
        // the field names for the DEFC (Draconic Evolution Fusion Crafter) casings and the steam multiblock parts
        // have "tier" first or in the middle
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

    @Test
    void readsMetadataFieldTiers() {
        final Map<String, String> tiers = StructureTiers.read(new MTEEyeOfHarmony());
        assertEquals("8", tiers.get("spacetimeCompressionFieldMetadata"));
        assertEquals("7", tiers.get("timeAccelerationFieldMetadata"));
        assertFalse(tiers.containsKey("parallelAmount"));
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

    @Test
    void readsByNameAClassWhoseFieldTypeCannotLoadOnThisSide() throws ReflectiveOperationException {
        final Class<?> controller = Class
            .forName(ClientFieldController.class.getName(), false, new ServerSideLoader(false));
        assertEquals(Arrays.asList("casingTier", "glassTier"), StructureTiers.planKeys(controller));
        final Constructor<?> make = controller.getDeclaredConstructor();
        make.setAccessible(true);
        final Map<String, String> tiers = StructureTiers.read(make.newInstance());
        assertEquals("2", tiers.get("casingTier"));
        assertEquals("4", tiers.get("glassTier"));
        assertTrue(
            StructureTiers.unreadable(controller)
                .isEmpty());
        assertTrue(
            StructureTiers.unreadable(Controller.class)
                .isEmpty());
    }

    @Test
    void skipsAClassWhoseFieldsCanBeNeitherListedNorFound() throws ClassNotFoundException {
        final Class<?> controller = Class
            .forName(ClientFieldController.class.getName(), false, new ServerSideLoader(true));
        assertEquals(
            Collections.singletonList("glassTier"),
            StructureTiers.planKeys(controller),
            "the controller's own fields are skipped, its superclass is read");
        assertEquals(Collections.singletonList("ClientFieldController"), StructureTiers.unreadable(controller));
    }

    enum HeatingCoilLevel {
        LV,
        HV
    }

    static class Base {

        // StructureTiers reads baseTier, because Base is not one of the shared GT base classes
        int baseTier = 9;
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

    @SuppressWarnings("unused")
    static class MTEEyeOfHarmony {

        private int spacetimeCompressionFieldMetadata = 8;
        private int timeAccelerationFieldMetadata = 7;
        private long parallelAmount = 1;
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

    /**
     * Define ClientFieldController and its base in this loader, and refuse ClientOnly. Every other class comes from the
     * loader of the test. With {@code hideClassFile}, the class file of ClientFieldController is not available as a
     * resource either.
     */
    private static final class ServerSideLoader extends ClassLoader {

        private final boolean hideClassFile;

        ServerSideLoader(boolean hideClassFile) {
            super(StructureTiersTest.class.getClassLoader());
            this.hideClassFile = hideClassFile;
        }

        @Override
        public InputStream getResourceAsStream(String name) {
            if (hideClassFile && name.equals(
                ClientFieldController.class.getName()
                    .replace('.', '/') + ".class")) {
                return null;
            }
            return super.getResourceAsStream(name);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (name.equals(ClientOnly.class.getName())) {
                throw new ClassNotFoundException(name + " for invalid side SERVER");
            }
            if (!(name.equals(ClientFieldController.class.getName()) || name.equals(ClientFieldBase.class.getName()))) {
                return super.loadClass(name, resolve);
            }
            synchronized (getClassLoadingLock(name)) {
                final Class<?> loaded = findLoadedClass(name);
                if (loaded != null) {
                    return loaded;
                }
                try (InputStream in = getParent().getResourceAsStream(name.replace('.', '/') + ".class")) {
                    final ByteArrayOutputStream out = new ByteArrayOutputStream();
                    final byte[] buffer = new byte[4096];
                    for (int count; (count = in.read(buffer)) > 0;) {
                        out.write(buffer, 0, count);
                    }
                    final byte[] bytes = out.toByteArray();
                    return defineClass(name, bytes, 0, bytes.length);
                } catch (IOException e) {
                    throw new ClassNotFoundException(name, e);
                }
            }
        }
    }
}
