package com.gtnhspeedrun.audit.trackers;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/**
 * The bounding box of the block positions StructureLib visits during a multiblock's structure check: the footprint
 * that multiblock_formed and multiblock_footprint report. A GT check pushes a frame for its controller, the
 * StructureUtility mixin feeds every visited position into the top frame, and the formation logger reads the box
 * before the frame is popped. A check nested inside another (a Nanochip module, a Purification Plant unit) gets its
 * own frame, so its positions never widen the outer box.
 *
 * <p>
 * Each StructureUtility.iterateV2 call opens a scratch level, and only a call that returns true merges its positions
 * into the level below. A multiblock whose length is variable finds its end with a probe that fails (the Assembly
 * Line's next slice, the LSC's next layer, the LINAC's next segment), and that failed probe stays out of the box.
 *
 * <p>
 * Server thread only, and nothing is allocated per check or per position. In singleplayer the client thread also
 * runs StructureLib (projector hints), and the hooks ignore it. A check that throws skips its pop, so the stack is
 * also cleared at the start of every server tick.
 */
public final class StructureBounds {

    private static final int MAX_FRAMES = 16;
    private static final int MAX_LEVELS = 16;

    private static final Frame[] FRAMES = new Frame[MAX_FRAMES];
    private static int depth;
    private static Frame top;
    private static volatile Thread serverThread;

    static {
        for (int i = 0; i < MAX_FRAMES; i++) {
            FRAMES[i] = new Frame();
        }
    }

    private StructureBounds() {}

    /** Called on the server thread when the server starts. */
    public static void onServerStart() {
        serverThread = Thread.currentThread();
        reset();
    }

    public static void reset() {
        while (depth > 0) {
            FRAMES[--depth].owner = null;
        }
        top = null;
    }

    /**
     * Opens a frame for one structure check of {@code owner}. A frame with {@code collect} false still hides the
     * frames below it, so a check that is not collected cannot leak positions into an outer one.
     */
    public static void push(Object owner, boolean collect) {
        if (Thread.currentThread() != serverThread) {
            return;
        }
        if (depth == MAX_FRAMES) {
            // deeper nesting than any known multiblock: the outer boxes would be wrong, so none is reported
            for (Frame f : FRAMES) {
                f.overflowed = true;
            }
            return;
        }
        final Frame f = FRAMES[depth++];
        f.owner = owner;
        f.collect = collect;
        f.overflowed = false;
        f.levels = 0;
        f.skippedLevels = 0;
        f.clear(0);
        top = f;
    }

    /** Closes the newest frame of {@code owner} and every frame above it. */
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
     * @return {minX, minY, minZ, maxX, maxY, maxZ}, inclusive and including the controller, or null when the newest
     *         frame of {@code owner} recorded no position (a multiblock that checks without StructureLib)
     */
    public static int[] boundsFor(Object owner, int controllerX, int controllerY, int controllerZ) {
        if (Thread.currentThread() != serverThread) {
            return null;
        }
        for (int i = depth - 1; i >= 0; i--) {
            final Frame f = FRAMES[i];
            if (f.owner != owner) {
                continue;
            }
            if (f.overflowed || !f.found[0]) {
                return null;
            }
            final int[] box = f.box;
            return new int[] { Math.min(box[0], controllerX), Math.min(box[1], controllerY),
                Math.min(box[2], controllerZ), Math.max(box[3], controllerX), Math.max(box[4], controllerY),
                Math.max(box[5], controllerZ) };
        }
        return null;
    }

    /** FML bus: clears frames a thrown check left open. */
    public static final class Ticker {

        @SubscribeEvent
        public void onServerTick(TickEvent.ServerTickEvent event) {
            if (event.phase == TickEvent.Phase.START) {
                reset();
            }
        }
    }

    // ------------------------------------------------------------------ StructureUtility.iterateV2 hooks

    public static void iterateStarted() {
        final Frame f = top;
        if (f == null || !f.collect || Thread.currentThread() != serverThread) {
            return;
        }
        if (f.levels == MAX_LEVELS) {
            f.skippedLevels++;
            return;
        }
        f.clear(++f.levels);
    }

    public static void visited(int x, int y, int z) {
        final Frame f = top;
        if (f == null || !f.collect || f.levels == 0 || Thread.currentThread() != serverThread) {
            return;
        }
        f.extend(f.levels, x, y, z, x, y, z);
    }

    public static void iterateFinished(boolean succeeded) {
        final Frame f = top;
        if (f == null || !f.collect || Thread.currentThread() != serverThread) {
            return;
        }
        if (f.skippedLevels > 0) {
            f.skippedLevels--;
            return;
        }
        if (f.levels == 0) {
            return;
        }
        final int level = f.levels--;
        if (succeeded && f.found[level]) {
            final int[] box = f.box;
            final int at = level * 6;
            f.extend(level - 1, box[at], box[at + 1], box[at + 2], box[at + 3], box[at + 4], box[at + 5]);
        }
    }

    /** Level 0 is the frame's result. Level n is the box of the nth open iterateV2 call. */
    private static final class Frame {

        Object owner;
        boolean collect;
        boolean overflowed;
        int levels;
        int skippedLevels;
        final int[] box = new int[(MAX_LEVELS + 1) * 6];
        final boolean[] found = new boolean[MAX_LEVELS + 1];

        void clear(int level) {
            found[level] = false;
        }

        void extend(int level, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
            final int at = level * 6;
            if (!found[level]) {
                found[level] = true;
                box[at] = minX;
                box[at + 1] = minY;
                box[at + 2] = minZ;
                box[at + 3] = maxX;
                box[at + 4] = maxY;
                box[at + 5] = maxZ;
                return;
            }
            box[at] = Math.min(box[at], minX);
            box[at + 1] = Math.min(box[at + 1], minY);
            box[at + 2] = Math.min(box[at + 2], minZ);
            box[at + 3] = Math.max(box[at + 3], maxX);
            box[at + 4] = Math.max(box[at + 4], maxY);
            box[at + 5] = Math.max(box[at + 5], maxZ);
        }
    }
}
