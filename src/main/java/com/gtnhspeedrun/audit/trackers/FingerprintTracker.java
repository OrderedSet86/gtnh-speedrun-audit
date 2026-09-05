package com.gtnhspeedrun.audit.trackers;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.gtnhspeedrun.audit.core.AuditLogger;
import com.gtnhspeedrun.audit.core.JsonUtil;

import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.ModContainer;

/**
 * The session fingerprint: every mod jar's SHA-256 plus aggregate hashes of config/ and scripts/. A modified
 * GT jar, an edited recipe script or a tampered questbook (BQ's DefaultQuests lives under config/) changes a
 * hash that verifiers diff across sessions and against the known pack release.
 *
 * <p>
 * Hashing ~300 jars plus a config tree is multi-GB of IO — it runs on a background thread and lands as a
 * fingerprint line a few seconds into the session. A (size, mtime)-keyed cache makes every boot after the
 * first nearly free.
 */
public final class FingerprintTracker {

    private final AuditLogger logger;
    private final File cacheFile;
    private final List<String[]> mods = new ArrayList<>(); // {id, version, sourcePath}
    private final File configDir;
    private final File scriptsDir;

    private JsonObject cache = new JsonObject();
    private boolean cacheDirty;

    public FingerprintTracker(AuditLogger logger, File auditDir, File configDir, File scriptsDir) {
        this.logger = logger;
        this.cacheFile = new File(auditDir, "jarhash-cache.json");
        this.configDir = configDir;
        this.scriptsDir = scriptsDir;
    }

    /** Server thread: capture the mod list, then hand the file IO to a daemon thread. */
    public void start() {
        for (ModContainer mod : Loader.instance()
            .getActiveModList()) {
            final File source = mod.getSource();
            mods.add(
                new String[] { mod.getModId(), String.valueOf(mod.getVersion()),
                    source == null ? "" : source.getAbsolutePath() });
        }
        final Thread t = new Thread(this::run, "SpeedrunAudit-Fingerprint");
        t.setDaemon(true);
        t.start();
    }

    private void run() {
        try {
            loadCache();
            final JsonObject data = new JsonObject();

            final JsonArray modArr = new JsonArray();
            mods.sort(Comparator.comparing(m -> m[0]));
            for (String[] mod : mods) {
                final JsonObject o = new JsonObject();
                o.addProperty("id", mod[0]);
                o.addProperty("version", mod[1]);
                if (!mod[2].isEmpty()) {
                    final File source = new File(mod[2]);
                    o.addProperty("jar", source.getName());
                    // Dev workspaces load mods from directories; only real files get content hashes.
                    if (source.isFile()) {
                        o.addProperty("sha256", cachedHash(source));
                    }
                }
                modArr.add(o);
                if ("dreamcraft".equals(mod[0])) {
                    data.addProperty("packVersion", mod[1]);
                }
            }
            data.add("mods", modArr);
            data.addProperty("configDirHash", dirHash(configDir));
            data.addProperty("scriptsDirHash", dirHash(scriptsDir));
            saveCache();
            logger.log("fingerprint", data);
        } catch (Exception e) {
            final JsonObject data = new JsonObject();
            data.addProperty("error", String.valueOf(e));
            logger.log("fingerprint", data);
        }
    }

    /** Aggregate over (relative path, content hash) of every file, order-independent input, sorted walk. */
    private String dirHash(File dir) throws IOException {
        if (dir == null || !dir.isDirectory()) {
            return "absent";
        }
        final List<File> files = new ArrayList<>();
        collect(dir, files);
        files.sort(Comparator.comparing(File::getAbsolutePath));
        final StringBuilder sb = new StringBuilder(files.size() * 80);
        final int prefix = dir.getAbsolutePath()
            .length() + 1;
        for (File f : files) {
            sb.append(
                f.getAbsolutePath()
                    .substring(prefix))
                .append(':')
                .append(cachedHash(f))
                .append('\n');
        }
        return JsonUtil.sha256Hex(sb.toString());
    }

    private static void collect(File dir, List<File> into) {
        final File[] children = dir.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (child.isDirectory()) {
                collect(child, into);
            } else {
                into.add(child);
            }
        }
    }

    private String cachedHash(File file) throws IOException {
        final String key = file.getAbsolutePath();
        final String stamp = file.length() + ":" + file.lastModified();
        if (cache.has(key)) {
            final JsonObject entry = cache.getAsJsonObject(key);
            if (stamp.equals(
                entry.get("stamp")
                    .getAsString())) {
                return entry.get("sha256")
                    .getAsString();
            }
        }
        final String hash = JsonUtil.sha256Hex(Files.readAllBytes(file.toPath()));
        final JsonObject entry = new JsonObject();
        entry.addProperty("stamp", stamp);
        entry.addProperty("sha256", hash);
        cache.add(key, entry);
        cacheDirty = true;
        return hash;
    }

    private void loadCache() {
        try {
            if (cacheFile.isFile()) {
                cache = new JsonParser()
                    .parse(new String(Files.readAllBytes(cacheFile.toPath()), StandardCharsets.UTF_8))
                    .getAsJsonObject();
            }
        } catch (IOException | RuntimeException e) {
            cache = new JsonObject();
        }
    }

    private void saveCache() {
        if (!cacheDirty) {
            return;
        }
        try {
            Files.write(
                cacheFile.toPath(),
                JsonUtil.GSON.toJson(cache)
                    .getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {
            // Cache is an optimization; next boot just rehashes.
        }
    }

}
