package com.gtnhspeedrun.audit.harness;

import net.minecraft.world.World;

/** Name a block position in one world, for the self-test fixtures and the structure bench. */
public record WorldPos(World world, int x, int y, int z) {

    public WorldPos offset(int dx, int dy, int dz) {
        return new WorldPos(world, x + dx, y + dy, z + dz);
    }
}
