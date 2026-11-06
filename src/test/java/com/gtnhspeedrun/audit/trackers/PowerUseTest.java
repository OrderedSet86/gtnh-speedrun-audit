package com.gtnhspeedrun.audit.trackers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

/**
 * Test that {@link PowerUse} charges each machine the EU the machine consumed: a simulated world with known
 * consumption is measured, and the measured total must equal the simulated total. {@link FakeTile} stands in for a GT
 * tile, with a stored-EU field and nothing else. That double is the known gap: the real call order comes from the GT
 * mixins, checked by the pack self-test.
 */
class PowerUseTest {

    private static final int TICKS = 2_000;

    /**
     * Each tick, a standard multiblock drains energy hatches, a buffered controller moves hatch EU into the
     * controller's buffer and drains part of the buffer, a single-block machine drains itself, and a wireless hatch
     * fills from the network.
     * Amounts come from a seeded generator.
     */
    @Test
    void measuredEuEqualsSimulatedConsumption() {
        final long SEED = 20261031L;
        final long MAX_DRAIN = 1L << 40;
        final Random random = new Random(SEED);
        final PowerUse powerUse = new PowerUse(Thread.currentThread());
        final FakeTile multi = new FakeTile("multi", 0);
        final FakeTile multiHatch = new FakeTile("multiHatch", Long.MAX_VALUE);
        final FakeTile controller = new FakeTile("controller", 0);
        final FakeTile controllerHatch = new FakeTile("controllerHatch", Long.MAX_VALUE);
        final FakeTile machine = new FakeTile("machine", Long.MAX_VALUE);
        final FakeTile wirelessHatch = new FakeTile("wirelessHatch", 0);
        BigInteger consumed = BigInteger.ZERO;
        for (int tick = 0; tick < TICKS; tick++) {
            final long multiDrain = nextAmount(random, MAX_DRAIN);
            powerUse.enterTile(multi, multi.stored);
            drain(powerUse, multiHatch, multiDrain);
            powerUse.exitTile(multi.stored);

            final long moved = nextAmount(random, MAX_DRAIN);
            powerUse.enterTile(controller, controller.stored);
            drain(powerUse, controllerHatch, moved);
            controller.stored += moved;
            final long controllerDrain = nextAmount(random, controller.stored);
            drain(powerUse, controller, controllerDrain);
            powerUse.exitTile(controller.stored);

            final long machineDrain = nextAmount(random, MAX_DRAIN);
            powerUse.enterTile(machine, machine.stored);
            drain(powerUse, machine, machineDrain);
            powerUse.exitTile(machine.stored);

            final long pulled = nextAmount(random, MAX_DRAIN);
            powerUse.enterTile(wirelessHatch, wirelessHatch.stored);
            powerUse.noteWirelessDrain(BigInteger.valueOf(pulled));
            wirelessHatch.stored += pulled;
            powerUse.exitTile(wirelessHatch.stored);

            consumed = consumed.add(BigInteger.valueOf(multiDrain))
                .add(BigInteger.valueOf(controllerDrain))
                .add(BigInteger.valueOf(machineDrain));
        }

        final PowerUseInterval interval = powerUse.takeInterval(PowerUseTest::identify);

        assertEquals(consumed, interval.sumEu());
        assertEquals(BigInteger.ZERO, interval.sumWirelessEu());
        assertNull(
            interval.types()
                .get("wirelessHatch"));
    }

    @Test
    void sumsPastLongRangeStayExact() {
        final int DRAINS = 5;
        final PowerUse powerUse = new PowerUse(Thread.currentThread());
        final FakeTile multi = new FakeTile("multi", 0);
        final FakeTile hatch = new FakeTile("hatch", Long.MAX_VALUE);
        BigInteger consumed = BigInteger.ZERO;
        for (int i = 0; i < DRAINS; i++) {
            hatch.stored = Long.MAX_VALUE;
            powerUse.enterTile(multi, multi.stored);
            drain(powerUse, hatch, Long.MAX_VALUE);
            powerUse.exitTile(multi.stored);
            consumed = consumed.add(BigInteger.valueOf(Long.MAX_VALUE));
        }

        assertEquals(
            consumed,
            powerUse.takeInterval(PowerUseTest::identify)
                .sumEu());
    }

    @Test
    void onDemandWirelessDrainIsChargedAsWireless() {
        final BigInteger UPFRONT_EU = BigInteger.TEN.pow(30);
        final PowerUse powerUse = new PowerUse(Thread.currentThread());
        final FakeTile controller = new FakeTile("controller", 0);
        powerUse.enterTile(controller, controller.stored);
        powerUse.openRecipeCheck();
        powerUse.noteWirelessDrain(UPFRONT_EU);
        final BigInteger upfront = powerUse.closeRecipeCheck();
        powerUse.exitTile(controller.stored);

        final PowerUseInterval interval = powerUse.takeInterval(PowerUseTest::identify);

        assertEquals(UPFRONT_EU, upfront);
        assertEquals(UPFRONT_EU, interval.sumEu());
        assertEquals(UPFRONT_EU, interval.sumWirelessEu());
    }

    @Test
    void drainOutsideAFrameChargesTheDrainedTile() {
        final long EU = 300;
        final PowerUse powerUse = new PowerUse(Thread.currentThread());
        final FakeTile machine = new FakeTile("machine", EU);
        drain(powerUse, machine, EU);
        powerUse.noteWirelessDrain(BigInteger.valueOf(EU));

        final PowerUseInterval interval = powerUse.takeInterval(PowerUseTest::identify);

        assertEquals(BigInteger.valueOf(EU), interval.sumEu());
        assertEquals(
            1,
            interval.types()
                .get("machine")
                .machines());
    }

    @Test
    void callsFromAnotherThreadAreIgnored() {
        final PowerUse powerUse = new PowerUse(new Thread(() -> {}));
        final FakeTile machine = new FakeTile("machine", 1);
        powerUse.enterTile(machine, machine.stored);
        drain(powerUse, machine, 1);
        powerUse.exitTile(machine.stored);

        assertTrue(
            powerUse.takeInterval(PowerUseTest::identify)
                .isEmpty());
    }

    @Test
    void takeIntervalStartsANewInterval() {
        final PowerUse powerUse = new PowerUse(Thread.currentThread());
        final FakeTile machine = new FakeTile("machine", 1);
        final RecipeTally tally = powerUse.findRecipeTally(new RecipeKey("machine", "none"));
        final RecipeTallyCache cache = new RecipeTallyCache();
        cache.remember(null, 0, tally);
        tally.addStart(0, 1, 1);
        powerUse.enterTile(machine, machine.stored);
        drain(powerUse, machine, 1);
        powerUse.exitTile(machine.stored);

        assertFalse(
            powerUse.takeInterval(PowerUseTest::identify)
                .isEmpty());
        assertTrue(
            powerUse.takeInterval(PowerUseTest::identify)
                .isEmpty());
        assertNull(cache.findTally(null, 0));
    }

    @Test
    void recipeEuIsExactPastLongRange() {
        final long TICKS_PER_RECIPE = 3;
        final RecipeTally tally = new RecipeTally();
        tally.addStart(1, Long.MAX_VALUE, TICKS_PER_RECIPE);
        tally.addStart(1, -Long.MAX_VALUE, TICKS_PER_RECIPE);

        assertEquals(
            BigInteger.valueOf(Long.MAX_VALUE)
                .multiply(BigInteger.valueOf(TICKS_PER_RECIPE)),
            tally.eu()
                .toBigInteger());
        assertEquals(2, tally.starts());
    }

    @Test
    void multiblocksAndRecipesComeOutInCanonicalOrder() {
        final long SEED = 31L;
        final int TILES = 50;
        final Random random = new Random(SEED);
        final PowerUse powerUse = new PowerUse(Thread.currentThread());
        for (int i = 0; i < TILES; i++) {
            final FakeTile multi = new FakeTile("multi" + random.nextInt(5), 1);
            multi.x = random.nextInt(1000);
            drain(powerUse, multi, 1);
            powerUse.findRecipeTally(new RecipeKey("multi" + random.nextInt(5), "out" + random.nextInt(5)))
                .addStart(1, 1, 1);
        }

        final PowerUseInterval interval = powerUse.takeInterval(PowerUseTest::identify);

        final List<PowerUseInterval.MultiTotal> multis = interval.multis();
        for (int i = 1; i < multis.size(); i++) {
            final MachineSite before = multis.get(i - 1)
                .site();
            final MachineSite after = multis.get(i)
                .site();
            final int byName = before.formatTypeKey()
                .compareTo(after.formatTypeKey());
            assertTrue(byName < 0 || (byName == 0 && before.x() <= after.x()));
        }
        String previous = "";
        for (RecipeKey key : interval.recipes()
            .keySet()) {
            final String name = key.machine() + "\u0000" + key.output();
            assertTrue(previous.compareTo(name) < 0);
            previous = name;
        }
    }

    /** Return 0, 1, {@code max}, or a value in between, so every run has the boundary amounts. */
    private static long nextAmount(Random random, long max) {
        final int BOUNDARY_ODDS = 10;
        return switch (random.nextInt(BOUNDARY_ODDS)) {
            case 0 -> 0;
            case 1 -> Math.min(1, max);
            case 2 -> max;
            default -> max == 0 ? 0 : (random.nextLong() & Long.MAX_VALUE) % max;
        };
    }

    private static void drain(PowerUse powerUse, FakeTile tile, long eu) {
        powerUse.openDrain(tile.stored);
        tile.stored -= eu;
        powerUse.closeDrain(tile, tile.stored);
    }

    private static PowerUse.TileIdentity identify(PowerUseCounter counter) {
        final FakeTile tile = (FakeTile) counter;
        final boolean isMulti = tile.name.startsWith("multi") || tile.name.equals("controller");
        return new PowerUse.TileIdentity(
            tile.name,
            isMulti ? new MachineSite("FakeTile", tile.name, 0, tile.x, 0, 0) : null);
    }

    /** A GT tile with a stored-EU field. */
    private static final class FakeTile implements PowerUseCounter {

        private final String name;
        private long stored;
        private int x;
        private PowerTally tally;

        private FakeTile(String name, long stored) {
            this.name = name;
            this.stored = stored;
        }

        @Override
        public PowerTally audit$readPowerTally() {
            return tally;
        }

        @Override
        public void audit$writePowerTally(PowerTally tally) {
            this.tally = tally;
        }
    }
}
