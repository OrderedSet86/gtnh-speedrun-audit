package com.gtnhspeedrun.audit.trackers;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
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
 * fingerprint line a few seconds into the session. Every file is hashed by content on every boot: there is no
 * (size, mtime) cache, because an edit that keeps the size and resets the mtime would reuse the old hash.
 */
public final class FingerprintTracker {

    private final AuditLogger logger;
    private final List<String[]> mods = new ArrayList<>(); // {id, version, sourcePath}
    private final File configDir;
    private final File scriptsDir;

    public FingerprintTracker(AuditLogger logger, File configDir, File scriptsDir) {
        this.logger = logger;
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
                        o.addProperty("sha256", hashFile(source));
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
                .append(hashFile(f))
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

    private static String hashFile(File file) throws IOException {
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        // Streamed, so a large jar is never held in memory whole.
        final byte[] buffer = new byte[64 * 1024];
        try (InputStream in = new FileInputStream(file)) {
            int n;
            while ((n = in.read(buffer)) > 0) {
                digest.update(buffer, 0, n);
            }
        }
        return JsonUtil.toHex(digest.digest());
    }
}
