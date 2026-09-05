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

import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;

/**
 * Logs every command DISPATCH — player, console, RCON and command block all funnel through CommandEvent in
 * 1.7.10. Dispatch, not success: permission rejections thrown inside processCommand (ServerUtilities ranks)
 * still show up here uncanceled, which VERIFIERS.md documents.
 *
 * <p>
 * LOWEST + receiveCanceled so the recorded canceled flag is the final word after every other handler had its
 * say. May fire on the RCON thread — nothing here may touch world state beyond reading sender fields.
 */
public final class CommandTracker {

    private final AuditLogger logger;

    public CommandTracker(AuditLogger logger) {
        this.logger = logger;
    }

    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public void onCommand(CommandEvent event) {
        final ICommandSender sender = event.sender;
        // ClientCommandHandler fires this same event on the client thread for client-side commands in SP.
        final World world = sender == null ? null : sender.getEntityWorld();
        if (world != null && world.isRemote) {
            return;
        }

        final JsonObject data = new JsonObject();
        data.addProperty("cmd", event.command.getCommandName());
        final JsonArray args = new JsonArray();
        for (String p : event.parameters) {
            args.add(com.gtnhspeedrun.audit.core.JsonUtil.GSON.toJsonTree(p));
        }
        data.add("args", args);
        data.addProperty("canceled", event.isCanceled());
        describeSender(data, sender);
        logger.log("command", data);
    }

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
            addPos(data, sender.getPlayerCoordinates());
        } else if (sender instanceof CommandBlockLogic) {
            data.addProperty("senderType", "commandblock");
            data.addProperty("sender", sender.getCommandSenderName());
            addPos(data, sender.getPlayerCoordinates());
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

    private static void addPos(JsonObject data, ChunkCoordinates pos) {
        if (pos != null) {
            final JsonArray arr = new JsonArray();
            arr.add(com.gtnhspeedrun.audit.core.JsonUtil.GSON.toJsonTree(pos.posX));
            arr.add(com.gtnhspeedrun.audit.core.JsonUtil.GSON.toJsonTree(pos.posY));
            arr.add(com.gtnhspeedrun.audit.core.JsonUtil.GSON.toJsonTree(pos.posZ));
            data.add("pos", arr);
        }
    }
}
