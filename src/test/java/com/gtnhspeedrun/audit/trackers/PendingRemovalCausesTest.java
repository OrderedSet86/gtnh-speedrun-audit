package com.gtnhspeedrun.audit.trackers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import org.junit.jupiter.api.Test;

/** Test that a noted removal cause is taken once, latest note first, and that clear() forgets every note. */
class PendingRemovalCausesTest {

    private static final int SPOTS = 8;
    private static final int NOTES = 500;

    @Test
    void takeReturnsTheLatestNoteOnceThenNothing() {
        final long SEED = 20261025L;
        final Random random = new Random(SEED);
        final PendingRemovalCauses causes = new PendingRemovalCauses();
        final Map<BlockSpot, RemovalCause> latest = new HashMap<>();
        for (int i = 0; i < NOTES; i++) {
            final BlockSpot spot = randomSpot(random);
            if (random.nextBoolean()) {
                final RemovalCause cause = randomCause(random);
                causes.note(spot, cause);
                latest.put(spot, cause);
            } else {
                assertEquals(latest.remove(spot), causes.takeCause(spot));
                assertNull(causes.takeCause(spot));
            }
        }
    }

    @Test
    void takeOfASpotNeverNotedReturnsNull() {
        final PendingRemovalCauses causes = new PendingRemovalCauses();
        causes.note(new BlockSpot(0, 1, 2, 3), RemovalCause.ofWorld(RemovalCause.Cause.EXPLODED));

        // the same position in another dimension is another spot
        assertNull(causes.takeCause(new BlockSpot(-1, 1, 2, 3)));
    }

    @Test
    void clearForgetsEveryNote() {
        final long SEED = 7L;
        final Random random = new Random(SEED);
        final PendingRemovalCauses causes = new PendingRemovalCauses();
        for (int i = 0; i < NOTES; i++) {
            causes.note(randomSpot(random), randomCause(random));
        }
        causes.clear();

        for (int dim = 0; dim < SPOTS; dim++) {
            for (int x = 0; x < SPOTS; x++) {
                assertNull(causes.takeCause(new BlockSpot(dim, x, 0, 0)));
            }
        }
    }

    private static BlockSpot randomSpot(Random random) {
        return new BlockSpot(random.nextInt(SPOTS), random.nextInt(SPOTS), 0, 0);
    }

    private static RemovalCause randomCause(Random random) {
        final RemovalCause.Cause[] values = RemovalCause.Cause.values();
        return RemovalCause.ofWorld(values[random.nextInt(values.length)]);
    }
}
