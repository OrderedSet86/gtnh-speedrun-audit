package com.gtnhspeedrun.audit.compat;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

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
 * `/gt global_energy_add|set|join` move wireless EU by fiat. The command line alone says what was typed, not what
 * it did — a balance moved between teams (R5) was only provable from a chat screenshot. This reads every named
 * player's team balance at dispatch and again at the end of the tick, after the command ran.
 *
 * <p>
 * The API (WirelessNetworkManager.getUserEU, SpaceProjectManager.getLeader/getPlayerUUIDFromName) is the same
 * from GT 5.09.51.482 (pack 2.8.4) through current. Only instantiated when GregTech is loaded. CommandEvent may
 * arrive on the RCON thread, hence the synchronized hand-off to the server tick.
 */
public final class GtWirelessAudit {

    private static final class Pending {

        final String sub;
        final List<String> args;
        final List<Target> targets;

        Pending(String sub, List<String> args, List<Target> targets) {
            this.sub = sub;
            this.args = args;
            this.targets = targets;
        }
    }

    private static final class Target {

        final String name;
        final UUID uuid;
        final UUID leader;
        final BigInteger before;

        Target(String name, UUID uuid, UUID leader, BigInteger before) {
            this.name = name;
            this.uuid = uuid;
            this.leader = leader;
            this.before = before;
        }
    }

    private final AuditLogger logger;
    private final List<Pending> pending = new ArrayList<>();

    public GtWirelessAudit(AuditLogger logger) {
        this.logger = logger;
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onCommand(CommandEvent event) {
        final String cmd = event.command.getCommandName();
        if (!("gt".equals(cmd) || "gregtech".equals(cmd)) || event.parameters.length < 2
            || !event.parameters[0].startsWith("global_energy_")
            || event.sender.getEntityWorld() != null && event.sender.getEntityWorld().isRemote) {
            return;
        }
        // display and add/set name one player; join names the subject and the team it joins.
        final Set<String> names = new LinkedHashSet<>();
        names.add(event.parameters[1]);
        if ("global_energy_join".equals(event.parameters[0]) && event.parameters.length >= 3) {
            names.add(event.parameters[2]);
        }
        final List<Target> targets = new ArrayList<>();
        for (String name : names) {
            targets.add(resolve(name));
        }
        final List<String> args = new ArrayList<>();
        for (String p : event.parameters) {
            args.add(p);
        }
        synchronized (pending) {
            pending.add(new Pending(event.parameters[0], args, targets));
        }
    }

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
        for (Pending p : done) {
            final JsonObject data = new JsonObject();
            data.addProperty("sub", p.sub);
            final JsonArray args = new JsonArray();
            for (String a : p.args) {
                args.add(JsonUtil.GSON.toJsonTree(a));
            }
            data.add("args", args);
            final JsonArray targets = new JsonArray();
            for (Target t : p.targets) {
                final JsonObject o = new JsonObject();
                o.addProperty("name", t.name);
                if (t.uuid == null) {
                    o.addProperty("unresolved", true);
                } else {
                    o.addProperty("uuid", t.uuid.toString());
                    o.addProperty("teamLeaderBefore", String.valueOf(t.leader));
                    o.addProperty("before", String.valueOf(t.before));
                    final Target after = resolve(t.name);
                    o.addProperty("teamLeaderAfter", String.valueOf(after.leader));
                    o.addProperty("after", String.valueOf(after.before));
                }
                targets.add(o);
            }
            data.add("targets", targets);
            logger.log("gt_global_energy", data);
        }
    }

    private static Target resolve(String name) {
        try {
            final UUID uuid = SpaceProjectManager.getPlayerUUIDFromName(name);
            return new Target(name, uuid, SpaceProjectManager.getLeader(uuid), WirelessNetworkManager.getUserEU(uuid));
        } catch (RuntimeException e) {
            // Unknown name: the profile cache returns null and getId() NPEs. GT's own command fails the same way.
            return new Target(name, null, null, null);
        }
    }
}
