package com.gtnhspeedrun.audit.snapshot;

import java.util.HashSet;
import java.util.Set;

import net.minecraft.item.ItemStack;

import com.google.gson.JsonObject;
import com.gtnhspeedrun.audit.core.AuditLogger;
import com.gtnhspeedrun.audit.core.ChainAnchorData;

/**
 * The configured key-item watchlist. First sighting of each entry becomes a key_item_first_seen milestone;
 * the dedupe set rides in the anchor so a rollback legitimately re-arms the milestone.
 *
 * <p>
 * Config entries are {@code modid:name@meta}; {@code @meta} may be omitted to match any meta ("modid:name").
 */
public final class KeyItemIndex {

    private final Set<String> exact = new HashSet<>();
    private final Set<String> anyMeta = new HashSet<>();
    private final AuditLogger logger;
    private final ChainAnchorData anchor;

    public KeyItemIndex(String[] configEntries, AuditLogger logger, ChainAnchorData anchor) {
        this.logger = logger;
        this.anchor = anchor;
        for (String entry : configEntries) {
            final String trimmed = entry.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            (trimmed.contains("@") ? exact : anyMeta).add(trimmed);
        }
    }

    public boolean isEmpty() {
        return exact.isEmpty() && anyMeta.isEmpty();
    }

    /** Server thread only (mutates the anchor's dedupe set). */
    public void checkStack(ItemStack stack, String source, String playerUuid) {
        if (stack == null || isEmpty()) {
            return;
        }
        checkBase(ItemKey.base(stack), stack.getDisplayName(), source, playerUuid);
    }

    /** Key-only variant for scans that already have the string (AE2 census entries). */
    public void checkBase(String base, String displayName, String source, String playerUuid) {
        if (isEmpty()) {
            return;
        }
        final String matched;
        if (exact.contains(base)) {
            matched = base;
        } else {
            final int at = base.indexOf('@');
            final String noMeta = at < 0 ? base : base.substring(0, at);
            matched = anyMeta.contains(noMeta) ? noMeta : null;
        }
        if (matched == null || !anchor.seenKeyItems.add(matched)) {
            return;
        }
        anchor.markDirty();
        final JsonObject data = new JsonObject();
        data.addProperty("itemKey", base);
        data.addProperty("watchEntry", matched);
        data.addProperty("displayName", displayName);
        data.addProperty("source", source);
        if (playerUuid != null) {
            data.addProperty("playerUuid", playerUuid);
        }
        logger.log("key_item_first_seen", data);
    }
}
