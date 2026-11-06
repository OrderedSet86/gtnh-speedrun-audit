package com.gtnhspeedrun.audit.trackers;

import java.util.Arrays;

import org.jetbrains.annotations.Nullable;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/**
 * Track the block positions that StructureLib visits during a multiblock's structure check, and the bounding box of the
 * positions. The box is the {@code bounds} of the {@code multiblock_formed} and {@code multiblock_footprint} lines, and
 * the positions are the cells of the lines' {@code extents}.
 *
 * <p>
 * The GT mixins call {@link #push} for the controller at the start of a check. The StructureUtility mixin passes every
 * visited position to the top frame, and the formation logger calls {@link #computeBounds} before {@link #pop}. A check
 * nested inside another check, such as a Nanochip module or a Purification Plant unit, gets a separate frame, so the
 * positions of the nested check never widen the outer box.
 *
 * <p>
 * Each {@code StructureUtility.iterateV2} call opens a scratch level, and only a call that returns true merges the
 * call's positions into the level below. A multiblock of variable length ends the structure check with a probe that
 * fails, such as the Assembly Line's next slice, the LSC's next layer or the LINAC's next segment. The positions of
 * that failed probe stay out of the box and out of the frame's positions. The positions of all levels of a frame are in
 * one array, each level after the level below. A failed level is cut off the end of the array, and a succeeded level
 * stays in place.
 *
 * <p>
 * Call on the server thread only. The hooks allocate nothing per check or per position, except when a frame's position
 * array grows, up to {@link #MAX_POSITIONS} positions. In singleplayer the client thread also runs StructureLib for
 * projector hints, and the hooks return early on the client thread. A check that throws skips its pop, so
 * {@link Ticker} clears the stack at the start of every server tick.
 */
public final class StructureBounds {

    private static final int MAX_FRAMES = 16;
    private static final int MAX_LEVELS = 16;
    private static final int BOX_INTS = 6; // minX, minY, minZ, maxX, maxY, maxZ
    public static final int POSITION_INTS = 3; // x, y, z
    private static final int INITIAL_POSITIONS = 1024;
    public static final int MAX_POSITIONS = 1 << 20;

    // static: the GT and StructureLib mixins call the hooks with no instance. The frames are preallocated, so a
    // hook allocates nothing.
    private static final Frame[] FRAMES = new Frame[MAX_FRAMES];
    private static int depth; // server thread only: the count of open frames, reset at each server tick
    private static Frame top; // server thread only: FRAMES[depth - 1], or null with no open frame
    private static volatile Thread serverThread; // set at each server start. A hook on any other thread returns early

    static {
        for (int i = 0; i < MAX_FRAMES; i++) {
            FRAMES[i] = new Frame();
        }
    }

    private StructureBounds() {}

    /** Call on the server thread when the server starts. */
    public static void onServerStart() {
        serverThread = Thread.currentThread();
        reset();
    }

    private static void reset() {
        while (depth > 0) {
            FRAMES[--depth].owner = null;
        }
        top = null;
    }

    /**
     * Open a frame for one structure check of {@code owner}. A frame with {@code collect} false still becomes the top
     * frame, so the positions of an uncollected check stay out of the outer box.
     */
    public static void push(Object owner, boolean collect) {
        if (Thread.currentThread() != serverThread) {
            return;
        }
        if (depth == MAX_FRAMES) {
            // the nesting is deeper than any known multiblock's. The outer boxes would be wrong, so no box is reported
            for (Frame frame : FRAMES) {
                frame.overflowed = true;
            }
            return;
        }
        final Frame frame = FRAMES[depth++];
        frame.owner = owner;
        frame.collect = collect;
        frame.overflowed = false;
        frame.levels = 0;
        frame.skippedLevels = 0;
        frame.found[0] = false;
        frame.positionCount = 0;
        frame.positionsOverflowed = false;
        top = frame;
    }

    /** Close every frame from the newest frame of {@code owner} to the top frame. */
    public static void pop(Object owner) {
        if (Thread.currentThread() != serverThread) {
            return;
        }
        for (int i = depth - 1; i >= 0; i--) {
            if (FRAMES[i].owner != owner) {
                continue;
            }
            while (depth > i) {
                FRAMES[--depth].owner = null;
            }
            top = depth > 0 ? FRAMES[depth - 1] : null;
            return;
        }
    }

    /**
     * Return the sum, mod 2^64, of a mix of each visited position of the newest frame of {@code owner}. The sum is the
     * same for any order of one set of visits. Return 0 when {@link #copyPositions} would return null.
     */
    public static long computeExtentHash(Object owner) {
        final Frame frame = findPositionFrame(owner);
        if (frame == null) {
            return 0;
        }
        long hash = 0;
        for (int i = 0; i < frame.positionCount * POSITION_INTS; i += POSITION_INTS) {
            hash += mixPosition(frame.positions[i], frame.positions[i + 1], frame.positions[i + 2]);
        }
        return hash;
    }

    /**
     * Return the visited positions of the newest frame of {@code owner}, then the controller, as x, y, z triplets.
     * Return null off the server thread, or when the frame is missing, overflowed, past {@link #MAX_POSITIONS} or has
     * no position.
     */
    @Nullable
    public static int[] copyPositions(Object owner, MachineSite controller) {
        final Frame frame = findPositionFrame(owner);
        if (frame == null) {
            return null;
        }
        final int length = frame.positionCount * POSITION_INTS;
        final int[] positions = Arrays.copyOf(frame.positions, length + POSITION_INTS);
        positions[length] = controller.x();
        positions[length + 1] = controller.y();
        positions[length + 2] = controller.z();
        return positions;
    }

    private static @Nullable Frame findPositionFrame(Object owner) {
        if (Thread.currentThread() != serverThread) {
            return null;
        }
        for (int i = depth - 1; i >= 0; i--) {
            final Frame frame = FRAMES[i];
            if (frame.owner != owner) {
                continue;
            }
            return frame.overflowed || frame.positionsOverflowed || frame.positionCount == 0 ? null : frame;
        }
        return null;
    }

    // splitmix64's finalizer over the packed position: x and z in 26 bits each, y in 12 bits
    private static long mixPosition(int x, int y, int z) {
        final long X_Z_MASK = 0x3FFFFFFL;
        final long Y_MASK = 0xFFFL;
        long mixed = ((x & X_Z_MASK) << 38 | (z & X_Z_MASK) << 12 | (y & Y_MASK)) + 0x9E3779B97F4A7C15L;
        mixed = (mixed ^ (mixed >>> 30)) * 0xBF58476D1CE4E5B9L;
        mixed = (mixed ^ (mixed >>> 27)) * 0x94D049BB133111EBL;
        return mixed ^ (mixed >>> 31);
    }

    /**
     * @return {minX, minY, minZ, maxX, maxY, maxZ}, inclusive and including the controller, or null off the server
     *         thread, or when the newest frame of {@code owner} is missing, overflowed or has no position, such as for
     *         a multiblock that checks without StructureLib
     */
    public static int[] computeBounds(Object owner, MachineSite controller) {
        final int controllerX = controller.x();
        final int controllerY = controller.y();
        final int controllerZ = controller.z();
        if (Thread.currentThread() != serverThread) {
            return null;
        }
        for (int i = depth - 1; i >= 0; i--) {
            final Frame frame = FRAMES[i];
            if (frame.owner != owner) {
                continue;
            }
            if (frame.overflowed || !frame.found[0]) {
                return null;
            }
            final int[] box = frame.box;
            return new int[] { Math.min(box[0], controllerX), Math.min(box[1], controllerY),
                Math.min(box[2], controllerZ), Math.max(box[3], controllerX), Math.max(box[4], controllerY),
                Math.max(box[5], controllerZ) };
        }
        return null;
    }

    // ------------------------------------------------------------------ StructureUtility.iterateV2 hooks

    public static void iterateStarted() {
        final Frame frame = top;
        if (frame == null || !frame.collect || Thread.currentThread() != serverThread) {
            return;
        }
        if (frame.levels == MAX_LEVELS) {
            frame.skippedLevels++;
            return;
        }
        frame.found[++frame.levels] = false;
        frame.levelStart[frame.levels] = frame.positionCount;
    }

    public static void visited(int x, int y, int z) {
        final Frame frame = top;
        if (frame == null || !frame.collect || frame.levels == 0 || Thread.currentThread() != serverThread) {
            return;
        }
        frame.includePoint(x, y, z);
        frame.appendPosition(x, y, z);
    }

    public static void iterateFinished(boolean succeeded) {
        final Frame frame = top;
        if (frame == null || !frame.collect || Thread.currentThread() != serverThread) {
            return;
        }
        if (frame.skippedLevels > 0) {
            frame.skippedLevels--;
            return;
        }
        if (frame.levels == 0) {
            return;
        }
        final int level = frame.levels--;
        if (!succeeded) {
            frame.positionCount = frame.levelStart[level];
        } else if (frame.found[level]) {
            frame.mergeDown(level);
        }
    }

    /** Clear the frames that a thrown check left open, at the start of every server tick. Register on the FML bus. */
    public static final class Ticker {

        @SubscribeEvent
        public void onServerTick(TickEvent.ServerTickEvent event) {
            if (event.phase == TickEvent.Phase.START) {
                reset();
            }
        }
    }

    /** Level 0 is the frame's result. Level n is the box of the nth open iterateV2 call. */
    private static final class Frame {

        private Object owner;
        private boolean collect;
        private boolean overflowed;
        private int levels;
        private int skippedLevels;
        private final int[] box = new int[(MAX_LEVELS + 1) * BOX_INTS];
        private final boolean[] found = new boolean[MAX_LEVELS + 1];
        private int[] positions = new int[INITIAL_POSITIONS * POSITION_INTS];
        private int positionCount;
        private boolean positionsOverflowed;
        // the positionCount when the level's iterateV2 call started
        private final int[] levelStart = new int[MAX_LEVELS + 1];

        private void appendPosition(int x, int y, int z) {
            if (positionsOverflowed) {
                return;
            }
            final int at = positionCount * POSITION_INTS;
            if (at == positions.length) {
                if (positionCount == MAX_POSITIONS) {
                    positionsOverflowed = true;
                    return;
                }
                positions = Arrays.copyOf(positions, Math.min(positionCount * 2, MAX_POSITIONS) * POSITION_INTS);
            }
            positions[at] = x;
            positions[at + 1] = y;
            positions[at + 2] = z;
            positionCount++;
        }

        private void includePoint(int x, int y, int z) {
            final int at = levels * BOX_INTS;
            if (!found[levels]) {
                found[levels] = true;
                box[at] = x;
                box[at + 1] = y;
                box[at + 2] = z;
                box[at + 3] = x;
                box[at + 4] = y;
                box[at + 5] = z;
                return;
            }
            box[at] = Math.min(box[at], x);
            box[at + 1] = Math.min(box[at + 1], y);
            box[at + 2] = Math.min(box[at + 2], z);
            box[at + 3] = Math.max(box[at + 3], x);
            box[at + 4] = Math.max(box[at + 4], y);
            box[at + 5] = Math.max(box[at + 5], z);
        }

        private void mergeDown(int level) {
            final int from = level * BOX_INTS;
            final int to = (level - 1) * BOX_INTS;
            if (!found[level - 1]) {
                found[level - 1] = true;
                System.arraycopy(box, from, box, to, BOX_INTS);
                return;
            }
            final int AXES = 3;
            for (int axis = 0; axis < AXES; axis++) {
                box[to + axis] = Math.min(box[to + axis], box[from + axis]);
                box[to + AXES + axis] = Math.max(box[to + AXES + axis], box[from + AXES + axis]);
            }
        }
    }
}
