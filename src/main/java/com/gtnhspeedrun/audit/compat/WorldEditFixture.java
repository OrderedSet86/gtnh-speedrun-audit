package com.gtnhspeedrun.audit.compat;

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
 * The self-test's WorldEdit edit: a real EditSession, built through WorldEdit's own factory so it posts the same
 * EditSessionEvents a command does, writing 3 stone, 2 glass and 1 chest with tile data into the air. WorldEdit
 * types live here behind the Compat gate, so SelfTest stays loadable without WorldEdit.
 */
public final class WorldEditFixture {

    public static final int X = 20, Y = 80, Z = 20;

    private WorldEditFixture() {}

    /** @return null on success, otherwise what went wrong */
    public static String edit(World world) {
        // air first, so the removed counts are exactly 6 air whatever the terrain
        for (int dy = 0; dy < 3; dy++) {
            for (int dx = 0; dx < 3; dx++) {
                world.setBlockToAir(X + dx, Y + dy, Z);
            }
        }
        try {
            final EditSession session = WorldEdit.getInstance()
                .getEditSessionFactory()
                .getEditSession(ForgeWorldEdit.inst.getWorld(world), -1);
            final int stone = Block.getIdFromBlock(Blocks.stone);
            final int glass = Block.getIdFromBlock(Blocks.glass);
            for (int i = 0; i < 3; i++) {
                session.setBlock(new Vector(X + i, Y, Z), new BaseBlock(stone));
            }
            for (int i = 0; i < 2; i++) {
                session.setBlock(new Vector(X + i, Y + 1, Z), new BaseBlock(glass));
            }
            final Map<String, Tag> chest = new HashMap<>();
            chest.put("id", new StringTag("Chest"));
            session.setBlock(
                new Vector(X, Y + 2, Z),
                new BaseBlock(Block.getIdFromBlock(Blocks.chest), 0, new CompoundTag(chest)));
            session.flushQueue();
            return null;
        } catch (WorldEditException | RuntimeException e) {
            return String.valueOf(e);
        }
    }
}
