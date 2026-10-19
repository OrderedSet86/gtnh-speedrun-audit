package com.gtnhspeedrun.audit.snapshot;

import java.util.HashSet;
import java.util.Set;

import net.minecraft.item.ItemStack;

import com.google.gson.JsonObject;
import com.gtnhspeedrun.audit.core.AuditLogger;
import com.gtnhspeedrun.audit.core.ChainAnchorData;
import com.gtnhspeedrun.audit.core.JsonUtil;

/**
 * Match stacks against the configured key-item watchlist, and log the first sighting of each entry as a
 * {@code key_item_first_seen} milestone.
 *
 * <p>
 * The set of entries already seen is saved in the chain anchor. A rollback restores the anchor, so the milestone is
 * logged again after a rollback. Config entries are {@code modid:name@meta}, or {@code modid:name} to match any
 * meta.
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

    /** Call on the server thread only. The call adds to the anchor's {@code seenKeyItems} set. */
    public void checkStack(ItemStack stack, String source, String playerUuid) {
        if (stack == null || isEmpty()) {
            return;
        }
        checkBase(ItemKey.formatBaseKey(stack), stack.getDisplayName(), new Sighting(source, playerUuid));
    }

    /**
     * Check an item key in the {@link ItemKey#formatBaseKey} form, for scans that have the key and no stack. Call on
     * the server thread only.
     */
    public void checkBase(String base, String displayName, Sighting sighting) {
        if (isEmpty()) {
            return;
        }
        final String matched;
        if (exact.contains(base)) {
            matched = base;
        } else {
            final int metaSeparator = base.indexOf('@');
            final String noMeta = metaSeparator < 0 ? base : base.substring(0, metaSeparator);
            matched = anyMeta.contains(noMeta) ? noMeta : null;
        }
        if (matched == null || !anchor.seenKeyItems.add(matched)) {
            return;
        }
        anchor.markDirty();
        final JsonObject data = new JsonObject();
        data.addProperty("itemKey", base);
        data.addProperty("watchEntry", matched);
        data.addProperty("displayName", JsonUtil.stripFormatting(displayName));
        data.addProperty("source", sighting.source());
        if (sighting.playerUuid() != null) {
            data.addProperty("playerUuid", sighting.playerUuid());
        }
        logger.log("key_item_first_seen", data);
    }

    public boolean isEmpty() {
        return exact.isEmpty() && anyMeta.isEmpty();
    }

    /**
     * The source of a sighting, and the player.
     *
     * @param source     the scan or event, such as {@code inventory}, {@code craft} or {@code ae2}
     * @param playerUuid null when the source has no player
     */
    public record Sighting(String source, String playerUuid) {}
}
