package com.gtnhspeedrun.audit.trackers;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
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
 * Log the session fingerprint: the SHA-256 of every mod jar, and aggregate hashes of config/ and scripts/.
 *
 * <p>
 * A modified GT jar, an edited recipe script or a changed questbook changes a hash. BQ's DefaultQuests is under
 * config/. Verifiers diff the hashes across sessions and against the known pack release.
 *
 * <p>
 * The pack has about 300 jars, so hashing reads several GB. A background thread hashes the files, and the fingerprint
 * line comes a few seconds into the session. With a (size, mtime) cache, a file edited to the same size, with its mtime
 * restored, would get the stale hash. Every file is therefore hashed by content on every boot.
 */
public final class FingerprintTracker {

    private final AuditLogger logger;
    private final List<ModEntry> mods = new ArrayList<>();
    private final Path configDir;
    private final Path scriptsDir;

    public FingerprintTracker(AuditLogger logger, File configDir, File scriptsDir) {
        this.logger = logger;
        this.configDir = configDir == null ? null : configDir.toPath();
        this.scriptsDir = scriptsDir == null ? null : scriptsDir.toPath();
    }

    /** Call on the server thread. The call reads the mod list, and a daemon thread hashes the files. */
    public void start() {
        for (ModContainer mod : Loader.instance()
            .getActiveModList()) {
            final File source = mod.getSource();
            mods.add(
                new ModEntry(
                    mod.getModId(),
                    String.valueOf(mod.getVersion()),
                    source == null ? null
                        : source.toPath()
                            .toAbsolutePath()));
        }
        final Thread thread = new Thread(this::writeFingerprint, "SpeedrunAudit-Fingerprint");
        thread.setDaemon(true);
        // An unchecked exception or an Error ends the thread outside the IOException catch of writeFingerprint().
        // The handler writes the same failure line.
        thread.setUncaughtExceptionHandler((deadThread, failure) -> logFailure(failure));
        thread.start();
    }

    /**
     * Log the {@code fingerprint} event, or a {@code fingerprint} event with only an {@code error} field when a
     * file read fails. The thread's uncaught exception handler logs the error line for any other failure.
     */
    private void writeFingerprint() {
        try {
            final JsonObject data = new JsonObject();

            final JsonArray modArray = new JsonArray();
            mods.sort(Comparator.comparing(ModEntry::id));
            for (ModEntry mod : mods) {
                final JsonObject entry = new JsonObject();
                entry.addProperty("id", mod.id());
                entry.addProperty("version", mod.version());
                if (mod.source() != null) {
                    entry.addProperty(
                        "jar",
                        mod.source()
                            .getFileName()
                            .toString());
                    // a dev workspace loads mods from directories. Only a regular file gets a content hash
                    if (Files.isRegularFile(mod.source())) {
                        entry.addProperty("sha256", hashFile(mod.source()));
                    }
                }
                modArray.add(entry);
                if ("dreamcraft".equals(mod.id())) {
                    data.addProperty("packVersion", mod.version());
                }
            }
            data.add("mods", modArray);
            data.addProperty("configDirHash", hashDirectory(configDir));
            data.addProperty("scriptsDirHash", hashDirectory(scriptsDir));
            logger.log("fingerprint", data);
        } catch (IOException e) {
            logFailure(e);
        }
    }

    private void logFailure(Throwable failure) {
        final JsonObject data = new JsonObject();
        data.addProperty("error", String.valueOf(failure));
        logger.log("fingerprint", data);
    }

    /**
     * Return the SHA-256 of one {@code relative/path:sha256} line per file, in absolute-path order, or
     * {@code absent} when the directory doesn't exist. A directory that can't be listed adds no lines.
     */
    private static String hashDirectory(Path dir) throws IOException {
        if (dir == null || !Files.isDirectory(dir)) {
            return "absent";
        }
        final List<Path> files = new ArrayList<>();
        collectFiles(dir, files);
        files.sort(
            Comparator.comparing(
                file -> file.toAbsolutePath()
                    .toString()));
        final int AVERAGE_LINE_CHARS = 80;
        final StringBuilder lines = new StringBuilder(files.size() * AVERAGE_LINE_CHARS);
        final int prefix = dir.toAbsolutePath()
            .toString()
            .length() + 1;
        for (Path file : files) {
            lines.append(
                file.toAbsolutePath()
                    .toString()
                    .substring(prefix))
                .append(':')
                .append(hashFile(file))
                .append('\n');
        }
        return JsonUtil.sha256Hex(lines.toString());
    }

    private static void collectFiles(Path dir, List<Path> into) {
        final List<Path> children = new ArrayList<>();
        try (DirectoryStream<Path> listing = Files.newDirectoryStream(dir)) {
            for (Path child : listing) {
                children.add(child);
            }
        } catch (IOException | DirectoryIteratorException e) {
            return;
        }
        for (Path child : children) {
            if (Files.isDirectory(child)) {
                collectFiles(child, into);
            } else {
                into.add(child);
            }
        }
    }

    private static String hashFile(Path file) throws IOException {
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        final int BUFFER_BYTES = 64 * 1024;
        final byte[] buffer = new byte[BUFFER_BYTES];
        try (InputStream in = Files.newInputStream(file)) {
            int read;
            while ((read = in.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
            }
        }
        return JsonUtil.toHex(digest.digest());
    }

    // source is null for a mod with no source file
    private record ModEntry(String id, String version, Path source) {}
}
