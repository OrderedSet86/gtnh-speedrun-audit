package com.gtnhspeedrun.audit.trackers;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.server.MinecraftServer;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.gtnhspeedrun.audit.GtnhSpeedrunAudit;
import com.gtnhspeedrun.audit.core.JsonUtil;
import com.gtnhspeedrun.audit.core.SessionManager;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;

/**
 * Log each ServerUtilities /inv view from open to close, with what happened to its target during the view.
 *
 * <p>
 * A view edits a copy of an offline target's inventories. ServerUtilities writes the copy back only at close, and only
 * when the target is offline at close. Whether the viewer's moves reached the target depends on three things. The first
 * is the target's logins and logouts during the view. The second is other views of the same target, because the last
 * close overwrites the others. The third is how the view ended. A second /inv view replaces the open view without
 * closing the open view, and a viewer who disconnects never closes the view. So each view gets an id, and the mixin's
 * click lines have that id. The close line has the target's joins and leaves during the view, the ids of the
 * overlapping views, and how the view ended.
 *
 * <p>
 * The class uses only vanilla types. The InvseeContainer mixin calls the static methods, and the login listener is
 * on the FML bus.
 */
public final class InvseeViews {

    // static: the InvseeContainer mixin calls open(), countClick() and end() with no instance.
    // Server thread only: views are opened, clicked and closed on the server thread.
    private static final Map<String, View> OPEN = new LinkedHashMap<>();

    public InvseeViews() {
        // a new session starts with no views. A view left from an earlier session in this JVM never closed
        OPEN.clear();
    }

    @SubscribeEvent
    public void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        final UUID id = event.player.getUniqueID();
        for (View view : OPEN.values()) {
            if (id.equals(view.target)) {
                view.targetJoins++;
            }
        }
    }

    /** Count a target's logout, and end the viewer's views without a close when the viewer logs out. */
    @SubscribeEvent
    public void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        final UUID id = event.player.getUniqueID();
        for (View view : new ArrayList<>(OPEN.values())) {
            if (id.equals(view.target)) {
                view.targetLeaves++;
            }
        }
        for (String viewId : openViewsOf(id)) {
            final View view = OPEN.get(viewId);
            end(
                viewId,
                new Party(id, event.player.getCommandSenderName()),
                new ViewEnd("viewerLogout", null, isOnline(view.target)));
        }
    }

    /**
     * Log a view that was built.
     *
     * @return the view id, or null when no session is logging
     */
    public static String open(Party viewer, Party target, boolean targetOnline) {
        final SessionManager session = GtnhSpeedrunAudit.session();
        if (session == null) {
            return null;
        }
        final View view = new View(viewer.uuid(), target.uuid(), targetOnline);
        final String id = view.id;
        final JsonArray concurrent = new JsonArray();
        for (View other : OPEN.values()) {
            if (other.target != null && other.target.equals(target.uuid())) {
                other.overlapping.add(id);
                view.overlapping.add(other.id);
                concurrent.add(JsonUtil.GSON.toJsonTree(other.id));
            }
        }
        OPEN.put(id, view);
        final JsonObject data = describeParties(viewer, target);
        data.addProperty("viewId", id);
        data.addProperty("targetOnline", targetOnline);
        if (concurrent.size() > 0) {
            data.add("concurrentViews", concurrent);
        }
        session.logger()
            .log("invsee_open", data);
        return id;
    }

    /**
     * Count a click that changed a slot.
     *
     * @param touchedTarget true when the click changed a slot of the target
     */
    public static void countClick(String viewId, boolean touchedTarget) {
        final View view = viewId == null ? null : OPEN.get(viewId);
        if (view != null) {
            view.clicks++;
            if (touchedTarget) {
                view.targetClicks++;
            }
        }
    }

    /**
     * Log the end of a view. A second end of one view is skipped. The line has the target's uuid from the open.
     */
    public static void end(String viewId, Party viewer, ViewEnd ending) {
        final String how = ending.how();
        final boolean targetOnlineNow = ending.targetOnline();
        final View view = viewId == null ? null : OPEN.remove(viewId);
        final SessionManager session = GtnhSpeedrunAudit.session();
        if (view == null || session == null) {
            return;
        }
        final JsonObject data = describeParties(viewer, new Party(view.target, ending.targetName()));
        data.addProperty("viewId", view.id);
        data.addProperty("how", how);
        data.addProperty("clicks", view.clicks);
        data.addProperty("targetClicks", view.targetClicks);
        data.addProperty("targetOnlineAtOpen", view.targetOnlineAtOpen);
        data.addProperty("targetOnlineAtClose", targetOnlineNow);
        data.addProperty("targetJoins", view.targetJoins);
        data.addProperty("targetLeaves", view.targetLeaves);
        if (!view.targetOnlineAtOpen && targetOnlineNow) {
            data.addProperty("targetJoinedDuringView", true);
        }
        if (!view.overlapping.isEmpty()) {
            final JsonArray overlapping = new JsonArray();
            for (String id : view.overlapping) {
                overlapping.add(JsonUtil.GSON.toJsonTree(id));
            }
            data.add("overlappingViews", overlapping);
        }
        data.addProperty("outcome", describeOutcome(view, how, targetOnlineNow));
        session.logger()
            .log("invsee_close", data);
    }

    /** Return the ids of the viewer's open views. */
    public static List<String> openViewsOf(UUID viewer) {
        final List<String> ids = new ArrayList<>();
        for (View view : OPEN.values()) {
            if (view.viewer.equals(viewer)) {
                ids.add(view.id);
            }
        }
        return ids;
    }

    private static boolean isOnline(UUID player) {
        final MinecraftServer server = MinecraftServer.getServer();
        if (server == null || player == null) {
            return false;
        }
        for (Object rawPlayer : server.getConfigurationManager().playerEntityList) {
            if (player.equals(((EntityPlayer) rawPlayer).getUniqueID())) {
                return true;
            }
        }
        return false;
    }

    private static JsonObject describeParties(Party viewer, Party target) {
        final JsonObject data = new JsonObject();
        data.addProperty("uuid", String.valueOf(viewer.uuid()));
        data.addProperty("name", viewer.name());
        data.addProperty("targetUuid", String.valueOf(target.uuid()));
        if (target.name() != null) {
            data.addProperty("targetName", target.name());
        }
        return data;
    }

    /**
     * Return what the view's changes to the target came to, by ServerUtilities' rules for the main, armor, ender
     * chest, Baubles, Tinkers', Galacticraft and Battlegear tabs.
     *
     * <p>
     * At a close, ServerUtilities writes the view's inventory objects to the target's file, and only when the target
     * is offline at the close. For a target offline at open, the objects are a copy of the file. For a target online
     * at open, the objects are the target's live objects. The live objects go stale once the target logs out. The
     * Backpack mod and Adventure Backpack tabs follow other rules, and a reader judges those tabs per click.
     */
    private static String describeOutcome(View view, String how, boolean targetOnlineNow) {
        if (view.targetClicks == 0) {
            return "no target changes";
        }
        final boolean saved = "closed".equals(how) && !targetOnlineNow;
        if (view.targetOnlineAtOpen) {
            if (view.targetLeaves == 0) {
                return "live edit";
            }
            if (!saved) {
                return "changes after target logout lost";
            }
            return view.targetJoins > 0 ? "saved, target's later session overwritten" : "saved";
        }
        if (!"closed".equals(how)) {
            return "changes lost (view not closed)";
        }
        if (targetOnlineNow) {
            return "changes lost (target online at close)";
        }
        if (view.targetJoins > 0) {
            return "saved, target's session during the view overwritten";
        }
        return view.overlapping.isEmpty() ? "saved" : "saved, last writer wins";
    }

    private static final class View {

        private final String id;
        private final UUID viewer;
        private final UUID target;
        private final boolean targetOnlineAtOpen;
        private int targetJoins;
        private int targetLeaves;
        private int clicks;
        private int targetClicks;
        private final Set<String> overlapping = new LinkedHashSet<>();

        private View(UUID viewer, UUID target, boolean targetOnlineAtOpen) {
            final int VIEW_ID_LENGTH = 8;
            this.id = UUID.randomUUID()
                .toString()
                .substring(0, VIEW_ID_LENGTH);
            this.viewer = viewer;
            this.target = target;
            this.targetOnlineAtOpen = targetOnlineAtOpen;
        }
    }

    /** The viewer or the target of a view. A target with a null name gets no {@code targetName} on the line. */
    public record Party(UUID uuid, String name) {}

    /**
     * How a view ended, and the target's state at the end.
     *
     * <p>
     * {@code how} is {@code closed} when the viewer closed the view, {@code replaced} when a new /inv view replaced the
     * view without a close, and {@code viewerLogout} when the viewer disconnected with the view open. Only a close runs
     * ServerUtilities' write-back. With the other two endings, an offline target's copy is dropped with all its
     * changes.
     *
     * @param targetName null when the caller has no target name, and the line then has no {@code targetName}
     */
    public record ViewEnd(String how, String targetName, boolean targetOnline) {}
}
