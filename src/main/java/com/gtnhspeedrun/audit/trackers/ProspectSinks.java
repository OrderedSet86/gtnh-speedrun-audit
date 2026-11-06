package com.gtnhspeedrun.audit.trackers;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.IChatComponent;
import net.minecraft.world.World;

import org.jetbrains.annotations.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.gtnhspeedrun.audit.GtnhSpeedrunAudit;
import com.gtnhspeedrun.audit.core.JsonUtil;
import com.gtnhspeedrun.audit.core.SessionManager;
import com.gtnhspeedrun.audit.snapshot.ItemKey;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/**
 * Log one {@code prospect} line for each use of a GregTech ore prospecting tool or machine: the player, the place and
 * the scan's results.
 *
 * <p>
 * For a tool with chat output, such as the Prospector's Scanner or a prospector's hammer, the line has the chat lines
 * that the player received during the use. The map of the Electric Prospector's Scanner is a packet to the player's
 * GUI. On GT 5.09.52 and later, the scan finishes ticks after the right-click. The line for that scan has the packet's
 * centre, radius and mode, and the ore veins and fluids of the VisualProspecting results for the player in the same
 * tick. The line for the Advanced Seismic Prospector has the ore and oil lists written to the data stick. The line is
 * logged at the end of the use, or at the end of the tick for a scan with no open use.
 *
 * <p>
 * The signatures have only vanilla, JDK and audit types, so this class loads on a pack without GregTech. Every sink
 * runs on the server thread.
 */
public final class ProspectSinks {

    private static final int MAX_CHARS = 4096;
    private static final int MAX_LINES = 64;
    private static final int MAX_LIST_ENTRIES = 256;

    // static: the mixins call the sinks with no instance. All the sinks run on the server thread
    private static Scan openUse;
    // static, for the reason on `openUse`. onServerTick() empties the map at the end of each server tick
    private static final Map<EntityPlayer, Scan> SCANS_THIS_TICK = new IdentityHashMap<>();

    /** Flush the scans that no tool use was open for, at the end of each server tick. */
    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || SCANS_THIS_TICK.isEmpty()) {
            return;
        }
        final List<Scan> scans = new ArrayList<>(SCANS_THIS_TICK.values());
        SCANS_THIS_TICK.clear();
        for (Scan scan : scans) {
            log(scan);
        }
    }

    /**
     * Call at the head of a prospecting tool's or machine's use, with {@link #endToolUse} at the return. A use inside a
     * use adds a level to the outer use.
     */
    public static void beginToolUse(EntityPlayer player, Tool tool, Place place) {
        final int tick = readTick();
        if (openUse != null && openUse.tick == tick) {
            openUse.depth++;
            return;
        }
        // a use from an earlier tick got no endToolUse() call, because an exception escaped the tool's method. Drop the
        // use
        openUse = null;
        if (GtnhSpeedrunAudit.session() == null) {
            return;
        }
        openUse = new Scan(player, tool.name(), tick);
        if (tool.stack() != null) {
            openUse.data.addProperty("itemKey", ItemKey.formatBaseKey(tool.stack()));
        }
        place.addTo(openUse.data);
    }

    /** Log the use when anything was reported during the use. A use that only changed the tool's mode has no line. */
    public static void endToolUse() {
        final Scan scan = openUse;
        if (scan == null) {
            return;
        }
        if (scan.depth > 0) {
            scan.depth--;
            return;
        }
        openUse = null;
        if (scan.hasResult()) {
            log(scan);
        }
    }

    /** Keep a chat line that the player of the open use receives. */
    public static void captureLine(ICommandSender receiver, IChatComponent message) {
        final Scan scan = openUse;
        if (!(scan != null && message != null && receiver == scan.player)) {
            return;
        }
        if (scan.lines.size() >= MAX_LINES || scan.chars >= MAX_CHARS) {
            scan.data.addProperty("truncated", true);
            return;
        }
        String text = JsonUtil.stripFormatting(message.getUnformattedText());
        if (scan.chars + text.length() > MAX_CHARS) {
            text = text.substring(0, MAX_CHARS - scan.chars);
            scan.data.addProperty("truncated", true);
        }
        scan.lines.add(text);
        scan.chars += text.length();
    }

    /**
     * Report the map of an Electric Prospector's Scanner, sent to {@code player}: {@code fields} has the scan's centre
     * {@code x} and {@code z}, the centre's {@code chunkX} and {@code chunkZ}, the {@code radius} in chunks, and the
     * {@code mode}.
     */
    public static void reportScannerMap(EntityPlayer player, JsonObject fields) {
        final Scan scan = findScan(player, "electric_prospector");
        scan.data.addProperty("dim", player.dimension);
        for (Map.Entry<String, JsonElement> member : fields.entrySet()) {
            scan.data.add(member.getKey(), member.getValue());
        }
        scan.reported = true;
    }

    /**
     * Report the ore veins and underground fluids that VisualProspecting received for {@code player}. Each vein is
     * {@code {name, dim, chunkX, chunkZ}}, and each fluid {@code {fluid, dim, chunkX, chunkZ}}.
     */
    public static void reportVisualProspecting(EntityPlayer player, JsonArray veins, JsonArray fluids) {
        final Scan scan = findScan(player, "visualprospecting");
        scan.veins.addAll(veins);
        scan.fluids.addAll(fluids);
        scan.reported = true;
    }

    /**
     * Report the data that an Advanced Seismic Prospector wrote to a data stick. Each list of ore and oil entries is
     * cut at {@link #MAX_LIST_ENTRIES}.
     */
    public static void reportSeismicData(SeismicData stickData) {
        final Scan scan = openUse;
        if (scan == null) {
            return;
        }
        scan.data.addProperty("tier", stickData.tier());
        scan.data.addProperty("radius", stickData.radius());
        scan.data.add("ores", capList(stickData.ores(), scan));
        scan.data.add("oils", capList(stickData.oils(), scan));
        scan.reported = true;
    }

    private static int readTick() {
        final MinecraftServer server = MinecraftServer.getServer();
        return server == null ? -1 : server.getTickCounter();
    }

    /** Return the open use of {@code player}, else the player's scan of this tick, else a new scan for this tick. */
    private static Scan findScan(EntityPlayer player, String tool) {
        if (openUse != null && openUse.player == player) {
            return openUse;
        }
        return SCANS_THIS_TICK.computeIfAbsent(player, key -> new Scan(key, tool, readTick()));
    }

    private static JsonArray capList(List<String> entries, Scan scan) {
        final JsonArray capped = new JsonArray();
        for (String entry : entries) {
            if (capped.size() >= MAX_LIST_ENTRIES) {
                scan.data.addProperty("truncated", true);
                break;
            }
            capped.add(JsonUtil.GSON.toJsonTree(entry));
        }
        return capped;
    }

    /** Log the {@code prospect} line of {@code scan}. An empty list of chat lines, veins or fluids is left out. */
    private static void log(Scan scan) {
        final SessionManager session = GtnhSpeedrunAudit.session();
        if (session == null) {
            return;
        }
        final JsonObject data = new JsonObject();
        data.addProperty(
            "uuid",
            scan.player.getGameProfile()
                .getId()
                .toString());
        data.addProperty("name", scan.player.getCommandSenderName());
        data.addProperty("tool", scan.tool);
        for (Map.Entry<String, JsonElement> member : scan.data.entrySet()) {
            data.add(member.getKey(), member.getValue());
        }
        if (!scan.lines.isEmpty()) {
            final JsonArray lines = new JsonArray();
            for (String line : scan.lines) {
                lines.add(JsonUtil.GSON.toJsonTree(line));
            }
            data.add("lines", lines);
        }
        if (scan.veins.size() > 0) {
            data.add("veins", scan.veins);
        }
        if (scan.fluids.size() > 0) {
            data.add("fluids", scan.fluids);
        }
        session.logger()
            .log("prospect", data);
    }

    /** A prospecting tool's name in the log, and the tool's stack, or null for a machine. */
    public record Tool(String name, @Nullable ItemStack stack) {}

    /** The block a tool was used on, or the block of a machine, in a dimension. */
    public record Place(World world, int x, int y, int z) {

        private void addTo(JsonObject data) {
            data.addProperty("dim", world.provider.dimensionId);
            data.addProperty("x", x);
            data.addProperty("y", y);
            data.addProperty("z", z);
        }
    }

    /** An Advanced Seismic Prospector's data on a data stick. The radius is in blocks. */
    public record SeismicData(int tier, int radius, List<String> ores, List<String> oils) {}

    /** One use or scan, with the results reported so far, until the line is logged. */
    private static final class Scan {

        private final EntityPlayer player;
        private final String tool;
        private final JsonObject data = new JsonObject();
        private final List<String> lines = new ArrayList<>();
        private final JsonArray veins = new JsonArray();
        private final JsonArray fluids = new JsonArray();
        private final int tick;
        private int chars;
        private int depth;
        private boolean reported;

        private Scan(EntityPlayer player, String tool, int tick) {
            this.player = player;
            this.tool = tool;
            this.tick = tick;
        }

        private boolean hasResult() {
            return reported || !lines.isEmpty();
        }
    }
}
