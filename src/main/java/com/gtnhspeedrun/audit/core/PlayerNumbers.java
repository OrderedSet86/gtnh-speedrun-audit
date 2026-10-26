package com.gtnhspeedrun.audit.core;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Number the players of one session from 0, in join order, for the rows of {@code pos_sample}. The
 * {@code player_join} line has the number as {@code playerNumber}.
 */
public final class PlayerNumbers {

    private final Map<UUID, Integer> numbers = new HashMap<>();

    /** Return the player's number. A player with no number gets the next one. A player who rejoins keeps the number. */
    public int numberFor(UUID uuid) {
        return numbers.computeIfAbsent(uuid, key -> numbers.size());
    }
}
