package com.gtnhspeedrun.audit.trackers;

import net.minecraft.command.ICommandSender;
import net.minecraft.command.server.CommandBlockLogic;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.rcon.RConConsoleSource;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ChunkCoordinates;
import net.minecraft.world.World;
import net.minecraftforge.event.CommandEvent;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.gtnhspeedrun.audit.core.AuditLogger;
import com.gtnhspeedrun.audit.core.JsonUtil;

import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;

/**
 * Log every command dispatch. In 1.7.10, {@code CommandHandler.executeCommand()} posts {@code CommandEvent} for player,
 * console, RCON and command block commands. No event is posted for an unknown command or for a command that fails the
 * vanilla permission check, so neither is logged.
 *
 * <p>
 * A command is logged at dispatch, so a logged command can still fail. A permission rejection thrown inside
 * {@code processCommand}, such as a ServerUtilities rank check, is logged with {@code canceled} false. VERIFIERS.md
 * documents this case. The handler has priority LOWEST and receives canceled events, so the logged {@code canceled}
 * flag is the value after every other handler.
 *
 * <p>
 * The event can fire on the RCON thread. NEVER touch world state here beyond reading the sender's fields.
 */
public final class CommandTracker {

    private final AuditLogger logger;

    public CommandTracker(AuditLogger logger) {
        this.logger = logger;
    }

    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public void onCommand(CommandEvent event) {
        final ICommandSender sender = event.sender;
        // in singleplayer, ClientCommandHandler fires CommandEvent on the client thread for client-side commands
        final World world = sender == null ? null : sender.getEntityWorld();
        if (world != null && world.isRemote) {
            return;
        }

        final JsonObject data = new JsonObject();
        data.addProperty("cmd", event.command.getCommandName());
        final JsonArray args = new JsonArray();
        for (String parameter : event.parameters) {
            args.add(JsonUtil.GSON.toJsonTree(parameter));
        }
        data.add("args", args);
        data.addProperty("canceled", event.isCanceled());
        describeSender(data, sender);
        logger.log("command", data);
    }

    /** Add {@code senderType}, {@code sender}, and the position fields that the sender type has. */
    private static void describeSender(JsonObject data, ICommandSender sender) {
        if (sender instanceof EntityPlayerMP player) {
            data.addProperty("senderType", "player");
            data.addProperty("sender", player.getCommandSenderName());
            data.addProperty(
                "senderUuid",
                player.getGameProfile()
                    .getId()
                    .toString());
            data.addProperty("dim", player.dimension);
            addPosition(data, sender.getPlayerCoordinates());
        } else if (sender instanceof CommandBlockLogic) {
            data.addProperty("senderType", "commandblock");
            data.addProperty("sender", sender.getCommandSenderName());
            addPosition(data, sender.getPlayerCoordinates());
        } else if (sender instanceof RConConsoleSource) {
            data.addProperty("senderType", "rcon");
            data.addProperty("sender", sender.getCommandSenderName());
        } else if (sender instanceof MinecraftServer) {
            data.addProperty("senderType", "console");
            data.addProperty("sender", sender.getCommandSenderName());
        } else {
            data.addProperty("senderType", "other");
            data.addProperty("sender", sender == null ? "null" : sender.getCommandSenderName());
            data.addProperty(
                "senderClass",
                sender == null ? "null"
                    : sender.getClass()
                        .getName());
        }
    }

    private static void addPosition(JsonObject data, ChunkCoordinates coordinates) {
        if (coordinates != null) {
            final JsonArray position = new JsonArray();
            position.add(JsonUtil.GSON.toJsonTree(coordinates.posX));
            position.add(JsonUtil.GSON.toJsonTree(coordinates.posY));
            position.add(JsonUtil.GSON.toJsonTree(coordinates.posZ));
            data.add("pos", position);
        }
    }
}
