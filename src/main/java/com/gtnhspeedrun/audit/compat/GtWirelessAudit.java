package com.gtnhspeedrun.audit.compat;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import net.minecraft.world.World;
import net.minecraftforge.event.CommandEvent;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.gtnhspeedrun.audit.core.AuditLogger;
import com.gtnhspeedrun.audit.core.JsonUtil;

import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import gregtech.common.misc.WirelessNetworkManager;
import gregtech.common.misc.spaceprojects.SpaceProjectManager;

/**
 * Log the team EU balance of each player named in a {@code /gt global_energy_*} command, before and after the
 * command. The commands {@code global_energy_add}, {@code global_energy_set} and {@code global_energy_join} move
 * wireless EU by fiat. The logged command has what an operator typed. Without these balances, only a chat
 * screenshot would show how much EU moved between teams.
 *
 * <p>
 * The class reads each named player's team balance at dispatch, and again at the end of the tick after the command
 * ran. {@code CommandEvent} can arrive on the RCON thread, so the pending list is synchronized and the server tick
 * drains the list. The GT API that the class calls ({@code WirelessNetworkManager.getUserEU},
 * {@code SpaceProjectManager.getLeader} and {@code SpaceProjectManager.getPlayerUUIDFromName}) is the same from GT
 * 5.09.51.482 (pack 2.8.4) through current. The class references GT types, so construct it only when GregTech is
 * loaded.
 */
public final class GtWirelessAudit {

    private final AuditLogger logger;
    private final List<Pending> pending = new ArrayList<>();

    public GtWirelessAudit(AuditLogger logger) {
        this.logger = logger;
    }

    /** Read the balances of the players named in a {@code /gt global_energy_*} command, and queue the command. */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onCommand(CommandEvent event) {
        final String commandName = event.command.getCommandName();
        final boolean isGtCommand = "gt".equals(commandName) || "gregtech".equals(commandName);
        if (!(isGtCommand && event.parameters.length >= 2 && event.parameters[0].startsWith("global_energy_"))) {
            return;
        }
        final World senderWorld = event.sender.getEntityWorld();
        if (!(senderWorld == null || !senderWorld.isRemote)) {
            return;
        }
        // display, add and set have one player argument. join has the player, then a player of the team to join
        final Set<String> names = new LinkedHashSet<>();
        names.add(event.parameters[1]);
        if ("global_energy_join".equals(event.parameters[0]) && event.parameters.length >= 3) {
            names.add(event.parameters[2]);
        }
        final List<Target> targets = new ArrayList<>();
        for (String name : names) {
            targets.add(readTarget(name));
        }
        synchronized (pending) {
            pending.add(new Pending(event.parameters[0], List.of(event.parameters), targets));
        }
    }

    /**
     * Write one {@code gt_global_energy} line for each command queued since the last tick, with each target's team
     * leader and balance before the command and after the command.
     */
    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        final List<Pending> done;
        synchronized (pending) {
            if (pending.isEmpty()) {
                return;
            }
            done = new ArrayList<>(pending);
            pending.clear();
        }
        for (Pending command : done) {
            final JsonObject data = new JsonObject();
            data.addProperty("sub", command.subcommand());
            final JsonArray args = new JsonArray();
            for (String arg : command.args()) {
                args.add(JsonUtil.GSON.toJsonTree(arg));
            }
            data.add("args", args);
            final JsonArray targets = new JsonArray();
            for (Target target : command.targets()) {
                targets.add(describeTarget(target));
            }
            data.add("targets", targets);
            logger.log("gt_global_energy", data);
        }
    }

    private static JsonObject describeTarget(Target target) {
        final JsonObject described = new JsonObject();
        described.addProperty("name", target.name());
        final TeamBalance before = target.balance();
        if (before == null || before.uuid() == null) {
            described.addProperty("unresolved", true);
            return described;
        }
        described.addProperty(
            "uuid",
            before.uuid()
                .toString());
        described.addProperty("teamLeaderBefore", String.valueOf(before.leader()));
        described.addProperty("before", String.valueOf(before.eu()));
        final TeamBalance after = readTarget(target.name()).balance();
        described.addProperty("teamLeaderAfter", String.valueOf(after == null ? null : after.leader()));
        described.addProperty("after", String.valueOf(after == null ? null : after.eu()));
        return described;
    }

    private static Target readTarget(String name) {
        try {
            final UUID uuid = SpaceProjectManager.getPlayerUUIDFromName(name);
            return new Target(
                name,
                new TeamBalance(uuid, SpaceProjectManager.getLeader(uuid), WirelessNetworkManager.getUserEU(uuid)));
        } catch (RuntimeException e) { // noqa: NAR142, degrade and report over GT wireless code
            // For an unknown name the profile cache returns null, and getId() throws a NullPointerException. The GT
            // command fails the same way.
            return new Target(name, null);
        }
    }

    private record Pending(String subcommand, List<String> args, List<Target> targets) {

        private Pending {
            args = List.copyOf(args);
            targets = List.copyOf(targets);
        }
    }

    /** A player name from the command. {@code balance} is null when GT can't resolve the name. */
    private record Target(String name, TeamBalance balance) {}

    private record TeamBalance(UUID uuid, UUID leader, BigInteger eu) {}
}
