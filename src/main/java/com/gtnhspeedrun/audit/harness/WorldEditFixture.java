package com.gtnhspeedrun.audit.harness;

import java.util.HashMap;
import java.util.Map;

import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.world.World;

import com.sk89q.jnbt.CompoundTag;
import com.sk89q.jnbt.StringTag;
import com.sk89q.jnbt.Tag;
import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.Vector;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.WorldEditException;
import com.sk89q.worldedit.blocks.BaseBlock;
import com.sk89q.worldedit.forge.ForgeWorldEdit;

/**
 * Run the self-test's WorldEdit edit: a real EditSession that writes 3 stone, 2 glass and 1 chest with tile data
 * into the air. The session comes from {@code WorldEdit.getEditSessionFactory()}, so WorldEdit posts the same
 * EditSessionEvents as for a command. The class references WorldEdit types, so use the class only when WorldEdit is
 * loaded.
 */
public final class WorldEditFixture {

    private WorldEditFixture() {}

    /**
     * Edit at {@code pos} and up: a row of 3 stone, a row of 2 glass above the stone, and the chest on top.
     *
     * @return null on success, otherwise what went wrong
     */
    public static String edit(WorldPos pos) {
        final World world = pos.world();
        final int x = pos.x();
        final int y = pos.y();
        final int z = pos.z();
        final int STONE_COUNT = 3;
        final int GLASS_COUNT = 2;
        final int LAYER_COUNT = 3;
        final int NO_BLOCK_LIMIT = -1;
        // clear the 3 by 3 area to air first. The 6 removed blocks of the edit are then air on any terrain
        for (int dy = 0; dy < LAYER_COUNT; dy++) {
            for (int dx = 0; dx < STONE_COUNT; dx++) {
                world.setBlockToAir(x + dx, y + dy, z);
            }
        }
        try {
            final EditSession session = WorldEdit.getInstance()
                .getEditSessionFactory()
                .getEditSession(ForgeWorldEdit.inst.getWorld(world), NO_BLOCK_LIMIT);
            final int stone = Block.getIdFromBlock(Blocks.stone);
            final int glass = Block.getIdFromBlock(Blocks.glass);
            for (int i = 0; i < STONE_COUNT; i++) {
                session.setBlock(new Vector(x + i, y, z), new BaseBlock(stone));
            }
            for (int i = 0; i < GLASS_COUNT; i++) {
                session.setBlock(new Vector(x + i, y + 1, z), new BaseBlock(glass));
            }
            final Map<String, Tag> chest = new HashMap<>();
            chest.put("id", new StringTag("Chest"));
            session.setBlock(
                new Vector(x, y + 2, z),
                new BaseBlock(Block.getIdFromBlock(Blocks.chest), 0, new CompoundTag(chest)));
            session.flushQueue();
            return null;
        } catch (WorldEditException | RuntimeException e) { // noqa: NAR142, degrade and report over WorldEdit edit code
            return String.valueOf(e);
        }
    }
}
