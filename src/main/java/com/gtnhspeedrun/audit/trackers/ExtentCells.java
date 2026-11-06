package com.gtnhspeedrun.audit.trackers;

import static com.gtnhspeedrun.audit.trackers.StructureBounds.POSITION_INTS;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.Deflater;

import net.minecraft.block.Block;
import net.minecraft.world.World;

import org.jetbrains.annotations.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

/**
 * Encode the blocks of a multiblock as the {@code extents} of a formation or footprint line. The line's {@code bounds}
 * box has one cell for each block position, with the block at that position.
 *
 * <p>
 * {@code extentPalette} lists the block keys. A cell is an unsigned 16-bit little-endian value: 0 for no block, else
 * the palette index + 1. Cell {@code ((y - minY) * sizeZ + (z - minZ)) * sizeX + (x - minX)} is the block at (x, y, z).
 * {@code extents} is the cells, zlib-compressed, in base64. In Python:
 *
 * <pre>
 *     cells = np.frombuffer(zlib.decompress(base64.b64decode(extents)), '&lt;u2').reshape(sizeY, sizeZ, sizeX)
 * </pre>
 */
public final class ExtentCells {

    // 8 MB of cells while the line is encoded
    public static final int MAX_EXTENT_CELLS = 1 << 22;
    private static final int MAX_PALETTE_KEYS = 0xFFFF;

    private ExtentCells() {}

    /**
     * Return the key of the block at each x, y, z triplet of {@code positions}, in order. A GT machine block gets the
     * machine's {@code machineKey} as on a {@code machine_placed} line, and any other block {@code modid:name@meta}.
     * Air gets null.
     */
    public static List<String> readBlockKeys(World world, int[] positions) {
        final List<String> keys = new ArrayList<>(positions.length / POSITION_INTS);
        for (int i = 0; i < positions.length; i += POSITION_INTS) {
            keys.add(readBlockKey(world, positions, i));
        }
        return keys;
    }

    /** Return the key of the block at the triplet of {@code positions} that starts at index {@code at}. */
    private static @Nullable String readBlockKey(World world, int[] positions, int at) {
        final int x = positions[at];
        final int y = positions[at + 1];
        final int z = positions[at + 2];
        final Block block = world.getBlock(x, y, z);
        if (block.isAir(world, x, y, z)) {
            return null;
        }
        final String blockName = Block.blockRegistry.getNameForObject(block);
        if (MachinePlacementTracker.GT_MACHINE_BLOCK.equals(blockName)) {
            final String machineKey = MachinePlacementTracker
                .readGtMachine(block, block.getDamageValue(world, x, y, z), world.getTileEntity(x, y, z))
                .machineKey();
            if (machineKey != null) {
                return machineKey;
            }
        }
        return blockName + "@" + world.getBlockMetadata(x, y, z);
    }

    /**
     * Encode the cells of {@code bounds}. Return null when the box has more than {@link #MAX_EXTENT_CELLS} cells, or
     * the blocks have more than 65535 keys.
     *
     * @param bounds    {minX, minY, minZ, maxX, maxY, maxZ}, inclusive, with every position inside
     * @param positions x, y, z triplets, with repeats allowed
     * @param keys      the key of each triplet, null for no block
     */
    @Nullable
    public static Extents encodeExtents(int[] bounds, int[] positions, List<String> keys) {
        final int sizeX = bounds[3] - bounds[0] + 1;
        final int sizeY = bounds[4] - bounds[1] + 1;
        final int sizeZ = bounds[5] - bounds[2] + 1;
        if ((long) sizeX * sizeY * sizeZ > MAX_EXTENT_CELLS) {
            return null;
        }
        final char[] cells = new char[sizeX * sizeY * sizeZ];
        final List<String> palette = new ArrayList<>();
        final Map<String, Integer> paletteIndex = new HashMap<>();
        int blocks = 0;
        for (int i = 0; i < keys.size(); i++) {
            final String key = keys.get(i);
            if (key == null) {
                continue;
            }
            Integer index = paletteIndex.get(key);
            if (index == null) {
                if (palette.size() == MAX_PALETTE_KEYS) {
                    return null;
                }
                palette.add(key);
                index = palette.size();
                paletteIndex.put(key, index);
            }
            final int x = positions[POSITION_INTS * i] - bounds[0];
            final int y = positions[POSITION_INTS * i + 1] - bounds[1];
            final int z = positions[POSITION_INTS * i + 2] - bounds[2];
            final int cell = (y * sizeZ + z) * sizeX + x;
            if (cells[cell] == 0) {
                blocks++;
            }
            cells[cell] = (char) index.intValue();
        }
        return new Extents(compressCells(cells), palette, blocks);
    }

    private static String compressCells(char[] cells) {
        final byte[] littleEndian = new byte[cells.length * 2];
        for (int i = 0; i < cells.length; i++) {
            littleEndian[2 * i] = (byte) cells[i];
            littleEndian[2 * i + 1] = (byte) (cells[i] >>> 8);
        }
        final Deflater deflater = new Deflater();
        deflater.setInput(littleEndian);
        deflater.finish();
        final ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        final byte[] buffer = new byte[8192];
        while (!deflater.finished()) {
            compressed.write(buffer, 0, deflater.deflate(buffer));
        }
        deflater.end();
        return Base64.getEncoder()
            .encodeToString(compressed.toByteArray());
    }

    /**
     * The encoded cells of one multiblock.
     *
     * @param blocks the count of cells with a block
     */
    public record Extents(String cells, List<String> palette, int blocks) {

        public Extents {
            palette = List.copyOf(palette);
        }

        /** Write {@code extents}, {@code extentPalette} and {@code extentBlocks}. */
        public void writeTo(JsonObject data) {
            data.addProperty("extents", cells);
            final JsonArray keys = new JsonArray();
            for (String key : palette) {
                keys.add(new JsonPrimitive(key));
            }
            data.add("extentPalette", keys);
            data.addProperty("extentBlocks", blocks);
        }
    }
}
