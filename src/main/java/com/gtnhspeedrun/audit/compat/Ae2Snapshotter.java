package com.gtnhspeedrun.audit.compat;

import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.gtnhspeedrun.audit.core.AuditLogger;
import com.gtnhspeedrun.audit.core.JsonUtil;
import com.gtnhspeedrun.audit.snapshot.KeyItemIndex;

import appeng.api.networking.storage.IStorageGrid;
import appeng.api.storage.data.IAEFluidStack;
import appeng.api.storage.data.IAEItemStack;
import appeng.hooks.TickHandler;
import appeng.me.Grid;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/**
 * Periodic per-network census of every loaded AE2 grid: counts only, one grid captured per tick so an endgame
 * base with dozens of big networks never stalls a single tick. Only instantiated when AE2 is loaded.
 *
 * <p>
 * Cadence is coarser while the server is empty — item injection needs a player online; AFK automation drift
 * between censuses is expected and the bracketing censuses still bound it.
 *
 * <p>
 * getStorageList() is the network's cached aggregate (O(distinct types), no cell scans). Grid identity for
 * diffing across restarts is the persistent GridStorage id, reached through the one package-private accessor
 * ({@code Grid.getMyStorage()}) via a cached reflective call — Grid.getId() is random per instantiation.
 */
public final class Ae2Snapshotter {

    private final AuditLogger logger;
    private final KeyItemIndex keyItems;
    private final MinecraftServer server;
    private final long activeIntervalTicks;
    private final long idleIntervalTicks;

    private final ArrayDeque<Grid> pending = new ArrayDeque<>();
    private long tickCounter;
    private long lastCensusTick = Long.MIN_VALUE;
    private int censusIndex;
    private Method getMyStorage;
    private boolean reflectionFailed;

    public Ae2Snapshotter(AuditLogger logger, KeyItemIndex keyItems, MinecraftServer server, int activeMinutes,
        int idleMinutes) {
        this.logger = logger;
        this.keyItems = keyItems;
        this.server = server;
        this.activeIntervalTicks = activeMinutes * 60L * 20L;
        this.idleIntervalTicks = idleMinutes * 60L * 20L;
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        tickCounter++;
        if (!pending.isEmpty()) {
            captureOne();
            return;
        }
        final boolean anyoneOn = !server.getConfigurationManager().playerEntityList.isEmpty();
        final long interval = anyoneOn ? activeIntervalTicks : idleIntervalTicks;
        if (tickCounter - lastCensusTick >= interval) {
            requestCensus();
        }
    }

    /** Also wired to /audit snapshot. */
    public void requestCensus() {
        if (!pending.isEmpty()) {
            return;
        }
        lastCensusTick = tickCounter;
        censusIndex++;
        try {
            for (Grid grid : TickHandler.INSTANCE.getGridList()) {
                pending.add(grid);
            }
        } catch (RuntimeException | LinkageError e) {
            // AE2 not ticking yet (or API drift): skip this census, try again next interval.
            pending.clear();
        }
    }

    private void captureOne() {
        final Grid grid = pending.poll();
        if (grid == null) {
            return;
        }
        try {
            if (grid.isEmpty()) {
                return;
            }
            capture(grid);
        } catch (RuntimeException | LinkageError e) {
            // One broken grid must not void the rest of the census.
        }
    }

    private void capture(Grid grid) {
        final IStorageGrid storage = grid.getCache(IStorageGrid.class);
        if (storage == null) {
            return;
        }

        final List<String[]> items = new ArrayList<>();
        long totalItems = 0;
        for (IAEItemStack ae : storage.getItemInventory()
            .getStorageList()) {
            final Item item = ae.getItem();
            if (item == null) {
                continue;
            }
            String key = Item.itemRegistry.getNameForObject(item) + "@" + ae.getItemDamage();
            if (ae.hasTagCompound()) {
                final ItemStack stack = ae.getItemStack();
                if (stack != null && stack.hasTagCompound()) {
                    key += "#" + JsonUtil.canonicalNbtHash(stack.getTagCompound())
                        .substring(0, 16);
                }
            }
            items.add(new String[] { key, String.valueOf(ae.getStackSize()) });
            totalItems += ae.getStackSize();
            keyItems.checkBase(key.contains("#") ? key.substring(0, key.indexOf('#')) : key, key, "ae2", null);
        }

        final List<String[]> fluids = new ArrayList<>();
        for (IAEFluidStack ae : storage.getFluidInventory()
            .getStorageList()) {
            if (ae.getFluid() != null) {
                fluids.add(
                    new String[] { ae.getFluid()
                        .getName(), String.valueOf(ae.getStackSize()) });
            }
        }

        final String gridId = gridId(grid);
        final int nodeCount = grid.getNodes()
            .size();
        final int census = censusIndex;
        final JsonObject ref = new JsonObject();
        ref.addProperty("gridId", gridId);
        ref.addProperty("census", census);
        ref.addProperty("nodes", nodeCount);
        ref.addProperty("distinctItems", items.size());
        ref.addProperty("totalItems", totalItems);
        ref.addProperty("distinctFluids", fluids.size());

        final String fileName = "ae2-" + logger.clock()
            .get() + "-g" + gridId + ".json.gz";
        logger.logSnapshot("ae2_snapshot", fileName, () -> {
            final JsonObject snapshot = new JsonObject();
            snapshot.addProperty("gridId", gridId);
            snapshot.addProperty("census", census);
            snapshot.addProperty("nodes", nodeCount);
            final JsonArray itemArr = new JsonArray();
            for (String[] e : items) {
                final JsonObject o = new JsonObject();
                o.addProperty("key", e[0]);
                o.addProperty("count", Long.parseLong(e[1]));
                itemArr.add(o);
            }
            snapshot.add("items", itemArr);
            final JsonArray fluidArr = new JsonArray();
            for (String[] e : fluids) {
                final JsonObject o = new JsonObject();
                o.addProperty("fluid", e[0]);
                o.addProperty("mb", Long.parseLong(e[1]));
                fluidArr.add(o);
            }
            snapshot.add("fluids", fluidArr);
            return snapshot;
        }, ref);
    }

    private String gridId(Grid grid) {
        if (!reflectionFailed && getMyStorage == null) {
            try {
                getMyStorage = Grid.class.getDeclaredMethod("getMyStorage");
                getMyStorage.setAccessible(true);
            } catch (ReflectiveOperationException | RuntimeException e) {
                reflectionFailed = true;
            }
        }
        if (!reflectionFailed) {
            try {
                final Object storage = getMyStorage.invoke(grid);
                if (storage instanceof appeng.me.GridStorage gs) {
                    return String.valueOf(gs.getID());
                }
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // Fall through to the ephemeral id.
            }
        }
        // Ephemeral fallback: stable within this session only; the census index still allows within-session diffs.
        return "ephemeral-" + System.identityHashCode(grid);
    }
}
