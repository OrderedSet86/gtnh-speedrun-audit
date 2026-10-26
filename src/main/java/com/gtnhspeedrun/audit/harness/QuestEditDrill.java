package com.gtnhspeedrun.audit.harness;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.NBTTagCompound;

import com.gtnhspeedrun.audit.compat.Compat;

import betterquesting.api2.utils.Tuple2;
import betterquesting.network.handlers.NetQuestEdit;

/**
 * Send forged BetterQuesting quest edits for the self-test of the attempt limit, through the server handler that the
 * audit hooks. The betterquesting classes exist only when BetterQuesting is installed, so every reference to them is in
 * the nested {@code Holder} class, behind the {@code Compat.BETTER_QUESTING} guard.
 */
public final class QuestEditDrill {

    private QuestEditDrill() {}

    /**
     * Send one quest edit with a list of {@link NbtEditDrill#FLOOD_LIST_ELEMENTS} empty elements as {@code player},
     * then {@link NbtEditDrill#FLOOD_BURST} small ones. The payloads have no {@code action} key, so the handler takes
     * the default branch of the action switch and changes no quest, even for an op.
     *
     * @return the time the handler took for the large edit, in ms, or -1 without BetterQuesting
     */
    public static long sendForgedFlood(EntityPlayerMP player) throws IOException, ReflectiveOperationException {
        if (!Compat.BETTER_QUESTING.isLoaded()) {
            return -1;
        }
        final long start = System.nanoTime();
        Holder.sendEdit(player, NbtEditDrill.readEmptyElementList(NbtEditDrill.FLOOD_LIST_ELEMENTS));
        final long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        for (int packet = 0; packet < NbtEditDrill.FLOOD_BURST; packet++) {
            Holder.sendEdit(player, new NBTTagCompound());
        }
        return elapsedMs;
    }

    private static final class Holder {

        private static void sendEdit(EntityPlayerMP player, NBTTagCompound payload)
            throws ReflectiveOperationException {
            // onServer() is private. The packet registry calls it with the decoded payload and the sender
            final Method onServer = NetQuestEdit.class.getDeclaredMethod("onServer", Tuple2.class);
            onServer.setAccessible(true);
            try {
                onServer.invoke(null, new Tuple2<>(payload, player));
            } catch (InvocationTargetException e) {
                throw new ReflectiveOperationException("NetQuestEdit.onServer threw", e.getCause());
            }
        }
    }
}
