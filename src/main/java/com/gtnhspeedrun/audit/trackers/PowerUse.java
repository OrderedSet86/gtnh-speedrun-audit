package com.gtnhspeedrun.audit.trackers;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.function.Function;

import org.jetbrains.annotations.Nullable;

/**
 * Charge the EU that GT machines consume to the machine whose tick consumed the EU, and tally recipe starts, one
 * interval at a time.
 *
 * <p>
 * A tick of one GT tile is a frame, from {@link #enterTile} to {@link #exitTile}. In a frame, EU drained from another
 * tile, such as a multiblock's energy hatch, and wireless EU are drawn. EU drained from the frame tile's buffer is
 * self. At the end of the frame, the tile is charged with
 *
 * <pre>
 *     drawn + wireless + self - movedIn
 *     movedIn = (stored after - stored before + self), between 0 and drawn + wireless
 * </pre>
 *
 * A TecTech controller or a fusion reactor moves hatch EU into the controller's buffer, then drains the buffer.
 * With movedIn subtracted, that EU is charged once. A wireless energy hatch moves wireless EU into the hatch's buffer
 * and is charged nothing. movedIn is taken from the wireless EU first.
 *
 * <p>
 * Only the server thread is recorded. In singleplayer, the GT tiles of the multiblock preview world tick on the client
 * thread. Every call from another thread returns at once.
 */
public final class PowerUse {

    // static: the GT mixins call in with no instance. start() replaces the instance at each server start
    private static PowerUse active = new PowerUse(null);

    private final @Nullable Thread serverThread;
    private final List<PowerTally> listedTallies = new ArrayList<>();
    private final Map<RecipeKey, RecipeTally> recipes = new HashMap<>();
    private final EuSum frameDrawnEu = new EuSum();
    private final EuSum frameSelfEu = new EuSum();
    private @Nullable PowerUseCounter frameTile;
    private long frameStoredBefore;
    private @Nullable BigInteger frameWirelessEu;
    private long drainStoredBefore;
    private boolean recipeCheckOpen;
    private @Nullable BigInteger recipeWirelessEu;
    private int readErrors;

    public PowerUse(@Nullable Thread serverThread) {
        this.serverThread = serverThread;
    }

    /** Close the tallies of the previous server, and record the calls made on {@code serverThread} from now on. */
    public static PowerUse start(Thread serverThread) {
        active.closeTallies();
        active = new PowerUse(serverThread);
        return active;
    }

    public static PowerUse active() {
        return active;
    }

    public void enterTile(PowerUseCounter tile, long storedEu) {
        if (!isRecording()) {
            return;
        }
        frameTile = tile;
        frameStoredBefore = storedEu;
        frameDrawnEu.clear();
        frameSelfEu.clear();
        frameWirelessEu = null;
    }

    public boolean isRecording() {
        return Thread.currentThread() == serverThread;
    }

    public void openDrain(long storedEu) {
        if (isRecording()) {
            drainStoredBefore = storedEu;
        }
    }

    /**
     * Note the EU that one drain took from {@code drained}, the fall in stored EU since {@link #openDrain}. With no
     * frame open, as for a drain from a command or another mod's tile, the drained tile is charged.
     */
    public void closeDrain(PowerUseCounter drained, long storedEu) {
        if (!isRecording()) {
            return;
        }
        final long eu = drainStoredBefore - storedEu;
        if (eu <= 0) {
            return;
        }
        if (frameTile == null) {
            listTally(drained).eu()
                .add(eu);
        } else if (drained == frameTile) {
            frameSelfEu.add(eu);
        } else {
            frameDrawnEu.add(eu);
        }
    }

    /** Note wireless EU taken from a team's network. Outside a frame, as for a command, nothing is noted. */
    public void noteWirelessDrain(BigInteger eu) {
        if (!(isRecording() && frameTile != null)) {
            return;
        }
        frameWirelessEu = frameWirelessEu == null ? eu : frameWirelessEu.add(eu);
        if (recipeCheckOpen) {
            recipeWirelessEu = recipeWirelessEu == null ? eu : recipeWirelessEu.add(eu);
        }
    }

    public void exitTile(long storedEu) {
        if (!(isRecording() && frameTile != null)) {
            return;
        }
        final PowerUseCounter tile = frameTile;
        frameTile = null;
        if (frameDrawnEu.isZero() && frameSelfEu.isZero() && frameWirelessEu == null) {
            return;
        }
        if (frameWirelessEu == null && frameDrawnEu.fitsInLong() && frameSelfEu.fitsInLong()) {
            try {
                chargeFrameInLongs(tile, storedEu);
                return;
            } catch (ArithmeticException overflow) {
                // past 2^63, chargeFrameExactly() below repeats the sum in BigInteger
            }
        }
        chargeFrameExactly(tile, storedEu);
    }

    /** Forget an open frame. A tile tick that threw never reached {@link #exitTile}. */
    public void clearFrame() {
        frameTile = null;
        recipeCheckOpen = false;
    }

    public void openRecipeCheck() {
        if (isRecording()) {
            recipeCheckOpen = true;
            recipeWirelessEu = null;
        }
    }

    /** Return the wireless EU drained since {@link #openRecipeCheck}, or null for none. */
    public @Nullable BigInteger closeRecipeCheck() {
        if (!isRecording()) {
            return null;
        }
        recipeCheckOpen = false;
        final BigInteger upfrontEu = recipeWirelessEu;
        recipeWirelessEu = null;
        return upfrontEu;
    }

    /** Return the open tally of {@code key}, created on the first start of the interval. */
    public RecipeTally findRecipeTally(RecipeKey key) {
        return recipes.computeIfAbsent(key, newKey -> new RecipeTally());
    }

    public void noteReadError() {
        readErrors++;
    }

    /**
     * Return what the machines consumed and the recipes started since the last call, and start a new interval.
     * {@code classifier} names each tile that consumed EU. A classifier that cannot read a tile calls
     * {@link #noteReadError}, and the error counts in this interval.
     */
    public PowerUseInterval takeInterval(Function<PowerUseCounter, TileIdentity> classifier) {
        final Map<String, TypeSum> typeSums = new HashMap<>();
        final List<PowerUseInterval.MultiTotal> multis = new ArrayList<>();
        for (PowerTally tally : listedTallies) {
            final TileIdentity identity = classifier.apply(tally.tile());
            final BigInteger wirelessEu = tally.wirelessEu()
                .toBigInteger();
            final BigInteger eu = tally.eu()
                .toBigInteger()
                .add(wirelessEu);
            typeSums.computeIfAbsent(identity.machine(), machine -> new TypeSum())
                .add(eu, wirelessEu);
            if (identity.multiSite() != null) {
                multis.add(new PowerUseInterval.MultiTotal(identity.multiSite(), eu, wirelessEu));
            }
        }
        final SortedMap<String, PowerUseInterval.MachineTotal> types = new TreeMap<>();
        for (Map.Entry<String, TypeSum> entry : typeSums.entrySet()) {
            final TypeSum sum = entry.getValue();
            types.put(entry.getKey(), new PowerUseInterval.MachineTotal(sum.machines, sum.eu, sum.wirelessEu));
        }
        final SortedMap<RecipeKey, PowerUseInterval.RecipeTotal> recipeTotals = new TreeMap<>();
        for (Map.Entry<RecipeKey, RecipeTally> entry : recipes.entrySet()) {
            final RecipeTally tally = entry.getValue();
            recipeTotals.put(
                entry.getKey(),
                new PowerUseInterval.RecipeTotal(
                    tally.starts(),
                    tally.outputAmount(),
                    tally.eu()
                        .toBigInteger()));
        }
        final PowerUseInterval interval = new PowerUseInterval(types, multis, recipeTotals, readErrors);
        closeTallies();
        return interval;
    }

    private PowerTally listTally(PowerUseCounter tile) {
        PowerTally tally = tile.audit$readPowerTally();
        if (tally == null) {
            tally = new PowerTally(tile);
            tile.audit$writePowerTally(tally);
        }
        if (!tally.isListed()) {
            tally.setListed(true);
            listedTallies.add(tally);
        }
        return tally;
    }

    /** Charge the frame with long arithmetic. Throws ArithmeticException past 2^63. */
    private void chargeFrameInLongs(PowerUseCounter tile, long storedEu) {
        final long drawn = frameDrawnEu.readLong();
        final long self = frameSelfEu.readLong();
        final long received = Math.addExact(Math.subtractExact(storedEu, frameStoredBefore), self);
        final long movedIn = Math.min(Math.max(received, 0), drawn);
        final long consumed = Math.subtractExact(Math.addExact(drawn, self), movedIn);
        if (consumed > 0) {
            listTally(tile).eu()
                .add(consumed);
        }
    }

    /** Charge the frame with BigInteger arithmetic, for wireless EU and for sums past 2^63. */
    private void chargeFrameExactly(PowerUseCounter tile, long storedEu) {
        final BigInteger drawn = frameDrawnEu.toBigInteger();
        final BigInteger self = frameSelfEu.toBigInteger();
        final BigInteger wireless = frameWirelessEu == null ? BigInteger.ZERO : frameWirelessEu;
        final BigInteger received = BigInteger.valueOf(storedEu)
            .subtract(BigInteger.valueOf(frameStoredBefore))
            .add(self);
        final BigInteger movedIn = received.max(BigInteger.ZERO)
            .min(drawn.add(wireless));
        final BigInteger movedFromWireless = movedIn.min(wireless);
        final BigInteger movedFromDrawn = movedIn.subtract(movedFromWireless);
        final BigInteger consumed = drawn.subtract(movedFromDrawn)
            .add(self);
        final BigInteger wirelessConsumed = wireless.subtract(movedFromWireless);
        if (consumed.signum() == 0 && wirelessConsumed.signum() == 0) {
            return;
        }
        final PowerTally tally = listTally(tile);
        tally.eu()
            .add(consumed);
        tally.wirelessEu()
            .add(wirelessConsumed);
    }

    private void closeTallies() {
        for (PowerTally tally : listedTallies) {
            tally.eu()
                .clear();
            tally.wirelessEu()
                .clear();
            tally.setListed(false);
        }
        listedTallies.clear();
        for (RecipeTally tally : recipes.values()) {
            tally.close();
        }
        recipes.clear();
        readErrors = 0;
    }

    /**
     * Name a tile that consumed EU.
     *
     * @param machine   {@link MachineSite#formatTypeKey}
     * @param multiSite the controller's site for a multiblock, null for any other machine
     */
    public record TileIdentity(String machine, @Nullable MachineSite multiSite) {}

    private static final class TypeSum {

        private int machines;
        private BigInteger eu = BigInteger.ZERO;
        private BigInteger wirelessEu = BigInteger.ZERO;

        private void add(BigInteger tileEu, BigInteger tileWirelessEu) {
            machines++;
            eu = eu.add(tileEu);
            wirelessEu = wirelessEu.add(tileWirelessEu);
        }
    }
}
