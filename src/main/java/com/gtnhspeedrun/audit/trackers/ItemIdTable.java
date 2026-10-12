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
 * Numeric block and item IDs are per world, and NBT stores items by number (inventories, `ench`-bearing tools,
 * tile contents). Without this table a reviewer resolves them from the world's level.dat, which the bundle does
 * not carry. The map is the same one FML saves into level.dat: keys carry a \u0001 (block) or \u0002 (item)
 * prefix.
 *
 * <p>
 * Every session logs the hash; the file is written only when the hash differs from the one the anchor last saw,
 * so an unchanged pack costs one line per restart. The anchor rewinds with a rollback, which at worst rewrites
 * a table the log directory already holds.
 */
public final class ItemIdTable {

    private ItemIdTable() {}

    public static void log(AuditLogger logger, ChainAnchorData anchor, long ticks) {
        final Map<String, Integer> ids = new TreeMap<>(GameData.buildItemDataList().idMap);
        final String sha = hash(ids);
        final JsonObject ref = new JsonObject();
        ref.addProperty("sha256", sha);
        ref.addProperty("entries", ids.size());
        if (sha.equals(anchor.idTableSha256)) {
            ref.addProperty("unchanged", true);
            logger.log("item_id_table", ref);
            return;
        }
        anchor.idTableSha256 = sha;
        anchor.markDirty();
        final JsonObject table = new JsonObject();
        for (Map.Entry<String, Integer> e : ids.entrySet()) {
            table.addProperty(e.getKey(), e.getValue());
        }
        logger.logSnapshot("item_id_table", String.format("idtable-%d.json.gz", ticks), () -> table, ref);
    }

    static String hash(Map<String, Integer> sortedIds) {
        final StringBuilder sb = new StringBuilder(sortedIds.size() * 40);
        for (Map.Entry<String, Integer> e : sortedIds.entrySet()) {
            sb.append(e.getKey())
                .append('=')
                .append(e.getValue())
                .append('\n');
        }
        return JsonUtil.sha256Hex(
            sb.toString()
                .getBytes(StandardCharsets.UTF_8));
    }
}
