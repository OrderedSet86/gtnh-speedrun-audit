package com.gtnhspeedrun.audit.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/** Test that players are numbered from 0 in join order, and keep their number on a rejoin. */
class PlayerNumbersTest {

    @Test
    void numberIsTheOrderOfTheFirstJoin() {
        final Random random = new Random(46);
        final int CASES = 200;
        for (int i = 0; i < CASES; i++) {
            final List<UUID> players = new ArrayList<>();
            final int playerCount = 1 + random.nextInt(20);
            for (int player = 0; player < playerCount; player++) {
                players.add(new UUID(random.nextLong(), random.nextLong()));
            }
            final PlayerNumbers numbers = new PlayerNumbers();
            final List<UUID> firstJoins = new ArrayList<>();
            final int joinCount = 1 + random.nextInt(100);
            for (int join = 0; join < joinCount; join++) {
                final UUID player = players.get(random.nextInt(playerCount));
                if (!firstJoins.contains(player)) {
                    firstJoins.add(player);
                }
                assertEquals(firstJoins.indexOf(player), numbers.numberFor(player));
            }
        }
        // the boundary values: the first player is 0, and a player who rejoins before any other join keeps 0
        final PlayerNumbers numbers = new PlayerNumbers();
        final UUID first = new UUID(0, 1);
        assertEquals(0, numbers.numberFor(first));
        assertEquals(0, numbers.numberFor(first));
        assertEquals(1, numbers.numberFor(new UUID(0, 2)));
    }
}
