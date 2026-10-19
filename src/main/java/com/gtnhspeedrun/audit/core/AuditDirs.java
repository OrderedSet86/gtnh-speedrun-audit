package com.gtnhspeedrun.audit.core;

import java.io.IOException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Name the directories of one world's audit trail. The trail is outside the world folder. */
public record AuditDirs(Path root, Path log, Path snapshots, Path export) {

    public static AuditDirs under(Path root) {
        return new AuditDirs(root, root.resolve("log"), root.resolve("snapshots"), root.resolve("export"));
    }

    /** Return the entries of {@code dir} in directory order, or an empty list when the directory cannot be read. */
    public static List<Path> listEntriesOrEmpty(Path dir) {
        final List<Path> entries = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path entry : stream) {
                entries.add(entry);
            }
        } catch (IOException | DirectoryIteratorException e) {
            return new ArrayList<>();
        }
        return entries;
    }
}
