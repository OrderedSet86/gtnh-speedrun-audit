package com.gtnhspeedrun.audit.harness;

import net.minecraft.entity.player.EntityPlayerMP;

import com.gtnhspeedrun.audit.compat.Compat;

import serverutils.command.tp.CmdTplast;
import serverutils.data.TeleportType;
import serverutils.lib.data.ForgePlayer;
import serverutils.lib.data.Universe;
import serverutils.lib.math.TeleporterDimPos;
import serverutils.task.TeleportTask;

/**
 * Drive two real ServerUtilities teleports of a fake player for the self-test of the teleport mixins: a {@code /home}
 * TeleportTask with no warmup, then {@code /tpl} to coordinates. The serverutils classes exist only when
 * ServerUtilities is installed, so every reference to them is in the nested {@code Holder} class, behind the
 * {@code Compat.SERVER_UTILITIES} guard.
 */
public final class TeleportDrill {

    private TeleportDrill() {}

    /**
     * Teleport {@code player} to {@code home}, then with {@code /tpl} to {@code tpl}, both in the player's dimension.
     * Each spot is a block, and the player lands at the block's centre.
     *
     * @return the two feet positions {x, y, z} that the self-test expects in the {@code teleport} lines, or null
     *         without ServerUtilities
     */
    public static double[][] teleport(EntityPlayerMP player, WorldPos home, WorldPos tpl) {
        if (!Compat.SERVER_UTILITIES.isLoaded()) {
            return null;
        }
        final double BLOCK_CENTRE = 0.5;
        final double[] homeFeet = { home.x() + BLOCK_CENTRE, home.y(), home.z() + BLOCK_CENTRE };
        final double[] tplFeet = { tpl.x() + BLOCK_CENTRE, tpl.y(), tpl.z() + BLOCK_CENTRE };
        Holder.runHomeTask(player, homeFeet);
        Holder.runTpl(player, tplFeet);
        return new double[][] { homeFeet, tplFeet };
    }

    private static final class Holder {

        private static void runHomeTask(EntityPlayerMP player, double[] feet) {
            // execute() finds the player through the universe. A fake player never logs in, so add the player the way
            // /add_fake_player does
            final Universe universe = Universe.get();
            if (universe.getPlayer(player.getUniqueID()) == null) {
                final ForgePlayer forgePlayer = new ForgePlayer(
                    universe,
                    player.getUniqueID(),
                    player.getCommandSenderName());
                universe.players.put(forgePlayer.getId(), forgePlayer);
                forgePlayer.clearCache();
            }
            final TeleporterDimPos destination = TeleporterDimPos.of(feet[0], feet[1], feet[2], player.dimension);
            new TeleportTask(TeleportType.HOME, player, 0, destination, null).execute(universe);
        }

        private static void runTpl(EntityPlayerMP player, double[] feet) {
            // processCommand() is called directly: /tpl is for ops, and the vanilla permission check is in the
            // command handler
            new CmdTplast().processCommand(
                player,
                new String[] { String.valueOf(feet[0]), String.valueOf(feet[1]), String.valueOf(feet[2]) });
        }
    }
}
