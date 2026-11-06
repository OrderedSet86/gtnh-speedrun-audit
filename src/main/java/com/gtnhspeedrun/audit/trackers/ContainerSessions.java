package com.gtnhspeedrun.audit.trackers;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.WorldSettings;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;

import org.jetbrains.annotations.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.gtnhspeedrun.audit.GtnhSpeedrunAudit;
import com.gtnhspeedrun.audit.core.AuditLogger;
import com.gtnhspeedrun.audit.core.SessionManager;
import com.gtnhspeedrun.audit.snapshot.ItemKey;
import com.gtnhspeedrun.audit.snapshot.StackJson;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/**
 * Log what a player moved between the player's inventory and a container: a chest, a machine, a crafting table or any
 * other GUI with slots. The player's open container is compared at the end of each player tick, so a GUI opened by
 * vanilla, FML or a mod that sets the container directly is seen alike.
 *
 * <p>
 * When the GUI closes, the item counts by key of the container's slots outside the player's inventory, and of the
 * player's inventory, are compared with the counts at the open. A {@code container_session} line is logged when the
 * player's counts changed, and for a GUI opened or closed in creative:
 *
 * <pre>
 *     "container":"net.minecraft.inventory.ContainerChest","pos":[-6,64,12],"dim":0,"gamemode":"survival",
 *     "ticksOpen":20,"how":"closed","containerDelta":{"minecraft:emerald@0":-1},"playerDelta":{"minecraft:emerald@0":1}
 * </pre>
 *
 * Items that a machine produces or uses while the GUI is open are in {@code containerDelta} only. A GUI opened in
 * creative also gets a {@code container_open} line, and each click in a GUI by a creative player a
 * {@code container_click} line with the changed slots. The player's inventory screen and a ServerUtilities
 * {@code /inv view} are not logged here.
 */
public final class ContainerSessions {

    private static final int SPOT_MAX_AGE_TICKS = 20;

    // static: the click mixin has no instance. Clicks are handled on the server thread
    private static List<ItemStack> clickBefore;
    // static, for the reason on `clickBefore`
    private static ItemStack clickCursorBefore;

    private final AuditLogger logger;
    private final MinecraftServer server;
    private final Map<EntityPlayer, Session> open = new IdentityHashMap<>();
    private final Map<EntityPlayer, ClickedBlock> lastClicked = new HashMap<>();

    public ContainerSessions(AuditLogger logger, MinecraftServer server) {
        this.logger = logger;
        this.server = server;
    }

    /** Remember the block a player right-clicked, the usual way to open a block's GUI. */
    @SubscribeEvent
    public void onInteract(PlayerInteractEvent event) {
        if (event.action == PlayerInteractEvent.Action.RIGHT_CLICK_BLOCK && !event.world.isRemote) {
            lastClicked.put(
                event.entityPlayer,
                new ClickedBlock(event.world.provider.dimensionId, event.x, event.y, event.z, server.getTickCounter()));
        }
    }

    @SubscribeEvent
    public void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (!(event.phase == TickEvent.Phase.END && event.player instanceof EntityPlayerMP)) {
            return;
        }
        final EntityPlayer player = event.player;
        final Container now = player.openContainer;
        final Session was = open.get(player);
        if (was != null && was.container == now) {
            return;
        }
        if (was != null) {
            open.remove(player);
            close(player, was, "closed");
        }
        if (now != null && now != player.inventoryContainer && !(now instanceof InvseeViewHolder)) {
            open.put(player, openSession(player, now));
        }
    }

    @SubscribeEvent
    public void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        lastClicked.remove(event.player);
        final Session was = open.remove(event.player);
        if (was != null) {
            close(event.player, was, "logout");
        }
    }

    /** Copy the open container's slots and the cursor before a click. Call only on the server thread. */
    public static void clickHead(EntityPlayer player) {
        clickBefore = ContainerSlots.copySlots(player.openContainer);
        clickCursorBefore = StackJson.copy(player.inventory.getItemStack());
    }

    /** Log a {@code container_click} line when the click changed a slot or the cursor. */
    public static void clickReturn(EntityPlayer player, Click click) {
        final List<ItemStack> before = clickBefore;
        clickBefore = null;
        final SessionManager session = GtnhSpeedrunAudit.session();
        if (!(before != null && session != null)) {
            return;
        }
        final JsonArray changes = ContainerSlots
            .diffSlots(player.openContainer, before, new ContainerSlots.Owners(player, "player", "container"));
        final ItemStack cursorNow = player.inventory.getItemStack();
        if (changes.size() == 0 && ItemStack.areItemStacksEqual(clickCursorBefore, cursorNow)) {
            return;
        }
        final JsonObject data = describePlayer(player);
        data.addProperty(
            "container",
            player.openContainer.getClass()
                .getName());
        data.addProperty("slot", click.slot());
        data.addProperty("button", click.button());
        data.addProperty("mode", click.mode());
        data.add("changes", changes);
        data.add("cursorBefore", StackJson.toJson(clickCursorBefore));
        data.add("cursorAfter", StackJson.toJson(cursorNow));
        session.logger()
            .log("container_click", data);
    }

    private Session openSession(EntityPlayer player, Container container) {
        final Session session = new Session(player, container, findSpot(player, container));
        if ("creative".equals(session.gamemode)) {
            final JsonObject data = describeSession(player, session);
            logger.log("container_open", data);
        }
        return session;
    }

    private void close(EntityPlayer player, Session session, String how) {
        final SortedMap<String, Long> containerDelta = subtractCounts(
            countContainer(player, session.container),
            session.containerCounts);
        final SortedMap<String, Long> playerDelta = subtractCounts(countPlayer(player), session.playerCounts);
        final String gamemodeNow = describeGamemode(player);
        if (playerDelta.isEmpty() && !"creative".equals(session.gamemode) && !"creative".equals(gamemodeNow)) {
            return;
        }
        final JsonObject data = describeSession(player, session);
        data.addProperty("ticksOpen", server.getTickCounter() - session.openTick);
        data.addProperty("how", how);
        data.add("containerDelta", describeCounts(containerDelta));
        data.add("playerDelta", describeCounts(playerDelta));
        logger.log("container_session", data);
    }

    /**
     * Return the block of the GUI: the tile entity of the first container slot outside the player's inventory, else the
     * block the player right-clicked in the last {@link #SPOT_MAX_AGE_TICKS} ticks, else null. A double chest's or a
     * ModularUI machine's slots have no tile entity.
     */
    private @Nullable int[] findSpot(EntityPlayer player, Container container) {
        for (Object entry : container.inventorySlots) {
            final Slot slot = (Slot) entry;
            if (slot.inventory instanceof TileEntity tile) {
                return new int[] { tile.xCoord, tile.yCoord, tile.zCoord };
            }
        }
        final ClickedBlock clicked = lastClicked.get(player);
        if (clicked != null && clicked.dim() == player.dimension
            && server.getTickCounter() - clicked.tick() <= SPOT_MAX_AGE_TICKS) {
            return new int[] { clicked.x(), clicked.y(), clicked.z() };
        }
        return null;
    }

    private static JsonObject describeSession(EntityPlayer player, Session session) {
        final JsonObject data = describePlayer(player);
        data.addProperty(
            "container",
            session.container.getClass()
                .getName());
        if (session.spot != null) {
            final JsonArray position = new JsonArray();
            for (int coordinate : session.spot) {
                position.add(new JsonPrimitive(coordinate));
            }
            data.add("pos", position);
        }
        data.addProperty("gamemode", session.gamemode);
        return data;
    }

    private static JsonObject describePlayer(EntityPlayer player) {
        final JsonObject data = new JsonObject();
        data.addProperty(
            "uuid",
            player.getUniqueID()
                .toString());
        data.addProperty("name", player.getCommandSenderName());
        data.addProperty("dim", player.dimension);
        return data;
    }

    /** Return the game mode's name. A fake player's {@code NOT_SET} mode is survival. */
    private static String describeGamemode(EntityPlayer player) {
        if (!(player instanceof EntityPlayerMP playerMp)) {
            return "survival";
        }
        final WorldSettings.GameType mode = playerMp.theItemInWorldManager.getGameType();
        return mode == WorldSettings.GameType.NOT_SET ? "survival" : mode.getName();
    }

    /** Count the items by key in the container's slots outside the player's inventory. */
    private static Map<String, Long> countContainer(EntityPlayer player, Container container) {
        final Map<String, Long> counts = new HashMap<>();
        for (Object entry : container.inventorySlots) {
            final Slot slot = (Slot) entry;
            if (slot.inventory != player.inventory) {
                addCount(counts, slot.getStack());
            }
        }
        return counts;
    }

    /** Count the items by key in the player's main inventory, armour and cursor. */
    private static Map<String, Long> countPlayer(EntityPlayer player) {
        final Map<String, Long> counts = new HashMap<>();
        for (ItemStack stack : player.inventory.mainInventory) {
            addCount(counts, stack);
        }
        for (ItemStack stack : player.inventory.armorInventory) {
            addCount(counts, stack);
        }
        addCount(counts, player.inventory.getItemStack());
        return counts;
    }

    private static void addCount(Map<String, Long> counts, @Nullable ItemStack stack) {
        if (stack != null && stack.getItem() != null) {
            counts.merge(ItemKey.formatBaseKey(stack), (long) stack.stackSize, Long::sum);
        }
    }

    private static SortedMap<String, Long> subtractCounts(Map<String, Long> after, Map<String, Long> before) {
        final SortedMap<String, Long> delta = new TreeMap<>();
        for (Map.Entry<String, Long> entry : after.entrySet()) {
            delta.merge(entry.getKey(), entry.getValue(), Long::sum);
        }
        for (Map.Entry<String, Long> entry : before.entrySet()) {
            delta.merge(entry.getKey(), -entry.getValue(), Long::sum);
        }
        delta.values()
            .removeIf(count -> count == 0);
        return delta;
    }

    private static JsonObject describeCounts(SortedMap<String, Long> counts) {
        final JsonObject described = new JsonObject();
        for (Map.Entry<String, Long> entry : counts.entrySet()) {
            described.addProperty(entry.getKey(), entry.getValue());
        }
        return described;
    }

    /** The slot number, mouse button and click mode of a {@code C0EPacketClickWindow}. */
    public record Click(int slot, int button, int mode) {}

    private record ClickedBlock(int dim, int x, int y, int z, int tick) {}

    /** One open GUI, with the item counts at the open. */
    private final class Session {

        private final Container container;
        private final int[] spot;
        private final String gamemode;
        private final int openTick;
        private final Map<String, Long> containerCounts = new HashMap<>();
        private final Map<String, Long> playerCounts = new HashMap<>();

        private Session(EntityPlayer player, Container container, @Nullable int[] spot) {
            this.container = container;
            this.spot = spot;
            this.gamemode = describeGamemode(player);
            this.openTick = server.getTickCounter();
            containerCounts.putAll(countContainer(player, container));
            playerCounts.putAll(countPlayer(player));
        }
    }
}
