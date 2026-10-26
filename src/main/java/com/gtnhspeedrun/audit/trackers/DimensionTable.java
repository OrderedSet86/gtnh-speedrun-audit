package com.gtnhspeedrun.audit.trackers;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Map;

import net.minecraft.world.WorldProvider;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.DimensionManager;
import net.minecraftforge.event.world.WorldEvent;

import org.jetbrains.annotations.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.gtnhspeedrun.audit.GtnhSpeedrunAudit;
import com.gtnhspeedrun.audit.core.SessionManager;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;

/**
 * Log the registered dimensions once per session as {@code dim_table}, and each dimension that loads later in the
 * session as {@code dim_loaded}, so a reader can tell which mod a dimension ID such as 173 belongs to.
 *
 * <p>
 * A dimension's provider class is read without constructing a provider. A name and a save folder come only from a
 * loaded world's provider. The name is in the server's language. One {@code dims} entry from pack 2.9.0-RC-2:
 *
 * <pre>
 * {"id":173,"type":173,"loaded":true,"provider":"makeo.gadomancy.common.utils.world.WorldProviderTCEldrich",
 *  "name":"Outer Lands","saveFolder":"DIM173"}
 * </pre>
 */
public final class DimensionTable {

    private final SessionManager session;
    private boolean tableLogged;

    public DimensionTable(SessionManager session) {
        this.session = session;
    }

    /** Log the {@code dim_table} line. Call after every mod has registered dimensions. */
    public void logDimensionTable() {
        final Map<Integer, Class<? extends WorldProvider>> providerClasses = readProviderClasses();
        final Integer[] dims = DimensionManager.getStaticDimensionIDs();
        Arrays.sort(dims);
        final JsonArray rows = new JsonArray();
        for (int dim : dims) {
            final JsonObject row = new JsonObject();
            row.addProperty("id", dim);
            final int type = DimensionManager.getProviderType(dim);
            row.addProperty("type", type);
            final WorldServer world = DimensionManager.getWorld(dim);
            row.addProperty("loaded", world != null);
            if (world != null) {
                describeProvider(world.provider, row);
            } else if (providerClasses != null && providerClasses.get(type) != null) {
                row.addProperty(
                    "provider",
                    providerClasses.get(type)
                        .getName());
            }
            rows.add(row);
        }
        final JsonObject data = new JsonObject();
        data.add("dims", rows);
        session.logger()
            .log("dim_table", data);
        tableLogged = true;
    }

    /** Log a {@code dim_loaded} line for the first load of each dimension after the table, once per session. */
    @SubscribeEvent
    public void onWorldLoad(WorldEvent.Load event) {
        if (!(tableLogged && event.world instanceof WorldServer world && !GtnhSpeedrunAudit.isOnClientThread())) {
            return;
        }
        final int dim = world.provider.dimensionId;
        if (!session.isFirstTime("dim_loaded:" + dim)) {
            return;
        }
        final JsonObject data = new JsonObject();
        data.addProperty("dim", dim);
        describeProvider(world.provider, data);
        session.logger()
            .log("dim_loaded", data);
    }

    // in Forge 1614, the private static DimensionManager.providers maps each provider type to a provider class
    private static @Nullable Map<Integer, Class<? extends WorldProvider>> readProviderClasses() {
        try {
            final Field field = DimensionManager.class.getDeclaredField("providers");
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            final Map<Integer, Class<? extends WorldProvider>> providers = (Map<Integer, Class<? extends WorldProvider>>) field
                .get(null);
            return providers;
        } catch (NoSuchFieldException | IllegalAccessException e) {
            GtnhSpeedrunAudit.LOG.warn("dim_table.providers_unreadable", e);
            return null;
        }
    }

    /**
     * Write {@code provider}, then {@code name} and {@code saveFolder}. When getDimensionName() or getSaveFolder()
     * throws, {@code providerError} is written.
     */
    private static void describeProvider(WorldProvider provider, JsonObject data) {
        data.addProperty(
            "provider",
            provider.getClass()
                .getName());
        try {
            data.addProperty("name", provider.getDimensionName());
            // the overworld's save folder is null: the overworld's regions are in the world folder
            final String saveFolder = provider.getSaveFolder();
            if (saveFolder != null) {
                data.addProperty("saveFolder", saveFolder);
            }
        } catch (RuntimeException | LinkageError e) { // noqa: NAR142, degrade and report over another mod's
                                                      // WorldProvider
            data.addProperty("providerError", String.valueOf(e));
        }
    }
}
