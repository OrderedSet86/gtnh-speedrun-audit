package com.gtnhspeedrun.audit.core;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

import org.apache.logging.log4j.Logger;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

/**
 * Read the fork links between the audit chains in one speedrun-audit directory. A chain's fork link is in the
 * session_start line at seq 0 of the chain, and nowhere else.
 */
public final class ChainFamily {

    private ChainFamily() {}

    /**
     * Return the chains in {@code auditRoot} that forked from {@code parentUuid}, in directory order. A chain whose
     * files can't be read is skipped, and the count of skipped chains is logged.
     */
    public static List<ChainCopy> listCopies(Path auditRoot, String parentUuid, Logger log) {
        final ChainScan scan = readChains(auditRoot);
        final List<ChainCopy> copies = new ArrayList<>();
        int skipped = scan.unreadable();
        for (FamilyChain chain : scan.chains()) {
            if (!(chain.link() != null && chain.link()
                .parentUuid()
                .equals(parentUuid))) {
                continue;
            }
            final ChainTail tail;
            try {
                tail = ChainTail.read(
                    AuditDirs.under(auditRoot.resolve(chain.uuid()))
                        .log(),
                    log);
            } catch (IOException exception) {
                skipped++;
                continue;
            }
            copies.add(
                new ChainCopy(
                    chain.uuid(),
                    chain.folder(),
                    chain.link()
                        .parentSave()
                        .seq(),
                    tail.seq(),
                    tail.hash()));
        }
        if (skipped > 0) {
            log.warn("copies.unreadable skipped={} parent={}", skipped, parentUuid);
        }
        return copies;
    }

    /**
     * Return the chains linked to {@code uuid} by fork links, directly or through other chains, with the chain of
     * {@code uuid} first and the rest in directory order.
     */
    public static ChainScan listFamily(Path auditRoot, String uuid) {
        final ChainScan scan = readChains(auditRoot);
        final Map<String, FamilyChain> chainsByUuid = new HashMap<>();
        for (FamilyChain chain : scan.chains()) {
            chainsByUuid.put(chain.uuid(), chain);
        }
        final Set<String> familyUuids = new HashSet<>();
        final List<String> pending = new ArrayList<>(List.of(uuid));
        while (!pending.isEmpty()) {
            final String next = pending.remove(pending.size() - 1);
            if (!familyUuids.add(next)) {
                continue;
            }
            final FamilyChain chain = chainsByUuid.get(next);
            if (chain != null && chain.link() != null) {
                pending.add(
                    chain.link()
                        .parentUuid());
            }
            for (FamilyChain other : scan.chains()) {
                if (other.link() != null && other.link()
                    .parentUuid()
                    .equals(next)) {
                    pending.add(other.uuid());
                }
            }
        }
        final List<FamilyChain> family = new ArrayList<>();
        if (chainsByUuid.containsKey(uuid)) {
            family.add(chainsByUuid.get(uuid));
        }
        for (FamilyChain chain : scan.chains()) {
            if (familyUuids.contains(chain.uuid()) && !chain.uuid()
                .equals(uuid)) {
                family.add(chain);
            }
        }
        return new ChainScan(family, scan.unreadable());
    }

    /**
     * Return the fork links from {@code link} back to the first chain, oldest first. The walk stops at a parent with
     * no log in {@code auditRoot}, at a parent that is no copy, and at a parent seen before.
     */
    public static List<ForkLink> listAncestors(Path auditRoot, ForkLink link) throws IOException {
        final List<ForkLink> links = new ArrayList<>();
        final Set<String> seenUuids = new HashSet<>();
        ForkLink next = link;
        while (next != null && seenUuids.add(next.parentUuid())) {
            links.add(0, next);
            final Path parentLog = AuditDirs.under(auditRoot.resolve(next.parentUuid()))
                .log();
            next = Files.isDirectory(parentLog) ? readForkLink(parentLog) : null;
        }
        return links;
    }

    /** Return the fork link of the chain in {@code logDir}, or null for a chain that is no world copy. */
    @Nullable
    public static ForkLink readForkLink(Path logDir) throws IOException {
        final JsonObject chainStart = readChainStart(logDir);
        return chainStart == null ? null : ForkLink.parseForkLink(chainStart.get("fork"));
    }

    /** Return every chain directory in {@code auditRoot} with a log, in directory order. */
    private static ChainScan readChains(Path auditRoot) {
        final List<FamilyChain> chains = new ArrayList<>();
        int unreadable = 0;
        for (Path chainDir : AuditDirs.listEntriesOrEmpty(auditRoot)) {
            final Path logDir = AuditDirs.under(chainDir)
                .log();
            if (!Files.isDirectory(logDir)) {
                continue;
            }
            final JsonObject chainStart;
            try {
                chainStart = readChainStart(logDir);
            } catch (IOException exception) {
                unreadable++;
                continue;
            }
            chains.add(
                new FamilyChain(
                    chainDir.getFileName()
                        .toString(),
                    chainStart != null && JsonUtil.hasString(chainStart, "worldFolder") ? chainStart.get("worldFolder")
                        .getAsString() : null,
                    chainStart == null ? null : ForkLink.parseForkLink(chainStart.get("fork"))));
        }
        return new ChainScan(chains, unreadable);
    }

    /** Return the data of the session_start line at seq 0 in {@code logDir}, or null when no file has that line. */
    @Nullable
    private static JsonObject readChainStart(Path logDir) throws IOException {
        for (Path file : AuditDirs.listEntriesOrEmpty(logDir)) {
            if (!Verifier.CHAIN_FILE_NAME.matcher(
                file.getFileName()
                    .toString())
                .matches()) {
                continue;
            }
            final JsonObject firstLine = readFirstLine(file);
            if (firstLine != null && JsonUtil.hasNumber(firstLine, "seq")
                && firstLine.get("seq")
                    .getAsLong() == 0
                && JsonUtil.hasObject(firstLine, "data")) {
                return firstLine.getAsJsonObject("data");
            }
        }
        return null;
    }

    @Nullable
    private static JsonObject readFirstLine(Path file) throws IOException {
        final String rawLine;
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            rawLine = reader.readLine();
        }
        if (rawLine == null) {
            return null;
        }
        final JsonElement parsed;
        try {
            parsed = new JsonParser().parse(rawLine);
        } catch (JsonParseException exception) {
            return null;
        }
        return parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
    }

    /**
     * The chains of a scan.
     *
     * @param unreadable the chain directories whose files couldn't be read
     */
    public record ChainScan(List<FamilyChain> chains, int unreadable) {

        public ChainScan {
            chains = List.copyOf(chains);
        }
    }

    /**
     * One chain directory.
     *
     * @param folder the world folder of the chain's first session_start, or null when that line has none
     * @param link   the fork link, or null for a chain that is no world copy
     */
    public record FamilyChain(String uuid, @Nullable String folder, @Nullable ForkLink link) {}

    /**
     * A chain that forked from another chain, with the copy's log tail at the time of the read.
     *
     * @param folder the world folder of the copy's first session_start, or null when that line has none
     */
    public record ChainCopy(String uuid, @Nullable String folder, long parentSeq, long tailSeq, String tailHash) {

        /** Build one entry of the copies array. The run analyzer reads these keys, so NEVER rename one. */
        public JsonObject toJson() {
            final JsonObject copy = new JsonObject();
            copy.addProperty("uuid", uuid);
            if (folder != null) {
                copy.addProperty("folder", folder);
            }
            copy.addProperty("parentSeq", parentSeq);
            copy.addProperty("tailSeq", tailSeq);
            copy.addProperty("tailHash", tailHash);
            return copy;
        }
    }
}
