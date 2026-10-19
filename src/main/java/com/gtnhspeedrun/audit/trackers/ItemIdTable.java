package com.gtnhspeedrun.audit.trackers;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;

import com.google.gson.JsonObject;
import com.gtnhspeedrun.audit.core.AuditLogger;
import com.gtnhspeedrun.audit.core.ChainAnchorData;
import com.gtnhspeedrun.audit.core.JsonUtil;

import cpw.mods.fml.common.registry.GameData;

/**
 * Log the world's numeric block and item IDs, so a reviewer can resolve the numeric IDs in NBT.
 *
 * <p>
 * Numeric IDs are per world, and the NBT of inventories and tile contents has items by number. Without this table, a
 * reviewer needs the world's level.dat, and the bundle doesn't include level.dat. The map is the map that FML saves
 * into level.dat. A key starts with \u0001 for a block or \u0002 for an item.
 *
 * <p>
 * The hash is logged in every session. The table file is written only when the hash differs from the anchor's
 * {@code idTableSha256}, so an unchanged pack costs one line per restart. A rollback restores the anchor, so a
 * rollback can write a table that the log directory has already.
 */
public final class ItemIdTable {

    private ItemIdTable() {}

    /** Log the {@code item_id_table} line, and write the table file when the hash differs from the anchor's. */
    public static void log(AuditLogger logger, ChainAnchorData anchor, long ticks) {
        final Map<String, Integer> ids = new TreeMap<>(GameData.buildItemDataList().idMap);
        final String sha256 = hash(ids);
        final JsonObject reference = new JsonObject();
        reference.addProperty("sha256", sha256);
        reference.addProperty("entries", ids.size());
        if (sha256.equals(anchor.idTableSha256)) {
            reference.addProperty("unchanged", true);
            logger.log("item_id_table", reference);
            return;
        }
        anchor.idTableSha256 = sha256;
        anchor.markDirty();
        final JsonObject table = new JsonObject();
        for (Map.Entry<String, Integer> entry : ids.entrySet()) {
            table.addProperty(entry.getKey(), entry.getValue());
        }
        logger.logSnapshot(
            new AuditLogger.Snapshot(
                "item_id_table",
                String.format("idtable-%d.json.gz", ticks),
                () -> table,
                reference));
    }

    public static String hash(Map<String, Integer> sortedIds) {
        final int AVERAGE_LINE_CHARS = 40;
        final StringBuilder lines = new StringBuilder(sortedIds.size() * AVERAGE_LINE_CHARS);
        for (Map.Entry<String, Integer> entry : sortedIds.entrySet()) {
            lines.append(entry.getKey())
                .append('=')
                .append(entry.getValue())
                .append('\n');
        }
        return JsonUtil.sha256Hex(
            lines.toString()
                .getBytes(StandardCharsets.UTF_8));
    }
}
