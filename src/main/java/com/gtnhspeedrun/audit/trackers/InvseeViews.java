package com.gtnhspeedrun.audit.trackers;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.gtnhspeedrun.audit.GtnhSpeedrunAudit;
import com.gtnhspeedrun.audit.core.JsonUtil;
import com.gtnhspeedrun.audit.core.SessionManager;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;

/**
 * Every open ServerUtilities /inv view, from open to close, and what happened to its target meanwhile.
 *
 * <p>
 * The view edits a copy of an offline target's inventories and writes it back only at close, and only if the
 * target is offline then. Whether the viewer's moves reached the target therefore depends on the target's
 * logins and logouts during the view, on other views of the same target (the last close overwrites the others),
 * and on how the view ended: a second /inv view replaces the open one without closing it, and a viewer who
 * disconnects never closes it. So each view gets an id, every click carries it, and the close line counts the
 * target's joins and leaves during the view, names the views that overlapped it, and says how it ended.
 *
 * <p>
 * Vanilla types only: the InvseeContainer mixin calls in, and the login listener lives on the FML bus.
 */
public final class InvseeViews {

    private static final class View {

        final String id;
        final UUID viewer;
        final UUID target;
        final boolean targetOnlineAtOpen;
        int targetJoins;
        int targetLeaves;
        int clicks;
        int targetClicks;
        final Set<String> overlapping = new LinkedHashSet<>();

        View(String id, UUID viewer, UUID target, boolean targetOnlineAtOpen) {
            this.id = id;
            this.viewer = viewer;
            this.target = target;
            this.targetOnlineAtOpen = targetOnlineAtOpen;
        }
    }

    /** Open views by id, in open order. Server thread only: views open, click and close there. */
    private static final Map<String, View> OPEN = new LinkedHashMap<>();

    public InvseeViews() {
        // A new session starts with no views; any left from a previous one in this JVM were never closed.
        OPEN.clear();
    }

    /** A view was built. Returns its id, or null when no session is logging. */
    public static String open(UUID viewer, String viewerName, UUID target, String targetName, boolean targetOnline) {
        final SessionManager session = GtnhSpeedrunAudit.session();
        if (session == null) {
            return null;
        }
        final String id = UUID.randomUUID()
            .toString()
            .substring(0, 8);
        final View view = new View(id, viewer, target, targetOnline);
        final JsonArray concurrent = new JsonArray();
        for (View other : OPEN.values()) {
            if (other.target != null && other.target.equals(target)) {
                other.overlapping.add(id);
                view.overlapping.add(other.id);
                concurrent.add(JsonUtil.GSON.toJsonTree(other.id));
            }
        }
        OPEN.put(id, view);
        final JsonObject data = base(viewer, viewerName, target, targetName);
        data.addProperty("viewId", id);
        data.addProperty("targetOnline", targetOnline);
        if (concurrent.size() > 0) {
            data.add("concurrentViews", concurrent);
        }
        session.logger()
            .log("invsee_open", data);
        return id;
    }

    /** A click that changed something; {@code touchedTarget} when it changed a slot of the target's. */
    public static void clicked(String viewId, boolean touchedTarget) {
        final View view = viewId == null ? null : OPEN.get(viewId);
        if (view != null) {
            view.clicks++;
            if (touchedTarget) {
                view.targetClicks++;
            }
        }
    }

    /**
     * The view ended. {@code how}: closed (the viewer closed it), replaced (a new /inv view took its place without
     * closing it), viewerLogout (the viewer disconnected with it open). Only closed runs ServerUtilities' write-back;
     * the other two drop an offline target's copy with all its changes. A second end for the same view is ignored.
     */
    public static void end(String viewId, String how, UUID viewer, String viewerName, String targetName,
        boolean targetOnlineNow) {
        final View view = viewId == null ? null : OPEN.remove(viewId);
        final SessionManager session = GtnhSpeedrunAudit.session();
        if (view == null || session == null) {
            return;
        }
        final JsonObject data = base(viewer, viewerName, view.target, targetName);
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
        data.addProperty("outcome", outcome(view, how, targetOnlineNow));
        session.logger()
            .log("invsee_close", data);
    }

    /**
     * What the view's target-side changes came to, from ServerUtilities' rules for main, armor, ender chest, Baubles,
     * Tinkers', Galacticraft and Battlegear tabs. Close writes the view's inventory objects to the target's file,
     * and only when the target is offline at that moment. For a target offline at open those objects are a copy of
     * the file; for a target online at open they are the target's live objects, which go stale once the target logs
     * out. Backpack-mod and Adventure Backpack tabs follow their own rules and are judged per click by a reader.
     */
    static String outcome(View view, String how, boolean targetOnlineNow) {
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

    /** Views the viewer still had open when a new one opened or the viewer left. */
    public static List<String> openViewsOf(UUID viewer) {
        final List<String> ids = new ArrayList<>();
        for (View view : OPEN.values()) {
            if (view.viewer.equals(viewer)) {
                ids.add(view.id);
            }
        }
        return ids;
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

    /** A viewer leaving ends their views without a close; a target leaving is counted. */
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
            end(viewId, "viewerLogout", id, event.player.getCommandSenderName(), null, isOnline(view.target));
        }
    }

    private static boolean isOnline(UUID player) {
        final net.minecraft.server.MinecraftServer server = net.minecraft.server.MinecraftServer.getServer();
        if (server == null || player == null) {
            return false;
        }
        for (Object o : server.getConfigurationManager().playerEntityList) {
            if (player.equals(((net.minecraft.entity.player.EntityPlayer) o).getUniqueID())) {
                return true;
            }
        }
        return false;
    }

    private static JsonObject base(UUID viewer, String viewerName, UUID target, String targetName) {
        final JsonObject data = new JsonObject();
        data.addProperty("uuid", String.valueOf(viewer));
        data.addProperty("name", viewerName);
        data.addProperty("targetUuid", String.valueOf(target));
        if (targetName != null) {
            data.addProperty("targetName", targetName);
        }
        return data;
    }
}
