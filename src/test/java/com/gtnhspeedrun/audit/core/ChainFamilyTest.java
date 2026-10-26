package com.gtnhspeedrun.audit.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import javax.annotation.Nullable;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.JsonObject;

/** Test that the fork links read back form the tree of world copies that the test wrote. */
class ChainFamilyTest {

    private static final Logger LOG = LogManager.getLogger("ChainFamilyTest");

    @Test
    void copiesAncestorsAndFamilyFollowTheForkTree(@TempDir Path dir) throws IOException {
        final int CASES = 40;
        final Random random = new Random(37);
        for (int run = 0; run < CASES; run++) {
            final Path auditRoot = Files.createDirectory(dir.resolve("run" + run));
            final List<GeneratedChain> tree = writeForkTree(auditRoot, "a", random);
            // a second tree in the same directory, which no family of the first tree may include
            writeForkTree(auditRoot, "b", random);

            for (GeneratedChain chain : tree) {
                final Set<String> children = new HashSet<>();
                for (GeneratedChain other : tree) {
                    if (chain.uuid()
                        .equals(other.parentUuid())) {
                        children.add(other.uuid());
                    }
                }
                final Set<String> copies = new HashSet<>();
                for (ChainFamily.ChainCopy copy : ChainFamily.listCopies(auditRoot, chain.uuid(), LOG)) {
                    copies.add(copy.uuid());
                    assertEquals(lastSeqOf(tree, copy.uuid()), copy.tailSeq());
                }
                assertEquals(children, copies, "run " + run);

                final ForkLink link = ChainFamily.readForkLink(
                    AuditDirs.under(auditRoot.resolve(chain.uuid()))
                        .log());
                if (chain.parentUuid() == null) {
                    assertNull(link);
                } else {
                    final List<String> parents = new ArrayList<>();
                    for (ForkLink ancestor : ChainFamily.listAncestors(auditRoot, link)) {
                        parents.add(ancestor.parentUuid());
                    }
                    assertEquals(listPathFromRoot(tree, chain.parentUuid()), parents, "run " + run);
                }

                final ChainFamily.ChainScan family = ChainFamily.listFamily(auditRoot, chain.uuid());
                assertEquals(
                    chain.uuid(),
                    family.chains()
                        .get(0)
                        .uuid());
                final Set<String> familyUuids = new HashSet<>();
                for (ChainFamily.FamilyChain member : family.chains()) {
                    familyUuids.add(member.uuid());
                }
                assertEquals(readUuids(tree), familyUuids, "run " + run);
            }
        }
    }

    @Test
    void ancestorWalkStopsAtAMissingParent(@TempDir Path auditRoot) throws IOException {
        writeChain(auditRoot, "child", "gone", 3);
        final ForkLink link = ChainFamily.readForkLink(
            AuditDirs.under(auditRoot.resolve("child"))
                .log());

        final List<ForkLink> ancestors = ChainFamily.listAncestors(auditRoot, link);

        assertEquals(1, ancestors.size());
        assertEquals(
            "gone",
            ancestors.get(0)
                .parentUuid());
    }

    @Test
    void ancestorWalkEndsOnACycle(@TempDir Path auditRoot) throws IOException {
        writeChain(auditRoot, "x", "y", 2);
        writeChain(auditRoot, "y", "x", 2);
        final ForkLink link = ChainFamily.readForkLink(
            AuditDirs.under(auditRoot.resolve("x"))
                .log());

        assertEquals(
            2,
            ChainFamily.listAncestors(auditRoot, link)
                .size());
    }

    /**
     * Write a root chain and up to 6 copies. Each copy forks from a random earlier chain of the tree, so the root is
     * every chain's ancestor.
     */
    private static List<GeneratedChain> writeForkTree(Path auditRoot, String prefix, Random random) throws IOException {
        final List<GeneratedChain> tree = new ArrayList<>();
        final int chainCount = 1 + random.nextInt(7);
        for (int i = 0; i < chainCount; i++) {
            final String parentUuid = i == 0 ? null
                : tree.get(random.nextInt(i))
                    .uuid();
            final long lastSeq = random.nextInt(5);
            final GeneratedChain chain = new GeneratedChain(prefix + i, parentUuid, lastSeq);
            writeChain(auditRoot, chain.uuid(), parentUuid, lastSeq);
            tree.add(chain);
        }
        return tree;
    }

    private static void writeChain(Path auditRoot, String uuid, @Nullable String parentUuid, long lastSeq)
        throws IOException {
        final Path logDir = Files.createDirectories(
            AuditDirs.under(auditRoot.resolve(uuid))
                .log());
        final JsonObject start = new JsonObject();
        start.addProperty("worldFolder", "folder-" + uuid);
        if (parentUuid != null) {
            start.add(
                "fork",
                new ForkLink(parentUuid, "folder-" + parentUuid, new AnchorValues(1, 20, 1000), "hash").toJson());
        }
        final List<String> lines = new ArrayList<>();
        lines.add(formatLine(0, "session_start", start));
        for (long seq = 1; seq <= lastSeq; seq++) {
            lines.add(formatLine(seq, "dim_first_visit", new JsonObject()));
        }
        Files.write(logDir.resolve("audit-000001.jsonl"), lines, StandardCharsets.UTF_8);
    }

    private static List<String> listPathFromRoot(List<GeneratedChain> tree, String uuid) {
        final List<String> path = new ArrayList<>();
        for (String next = uuid; next != null; next = findChain(tree, next).parentUuid()) {
            path.add(0, next);
        }
        return path;
    }

    private static long lastSeqOf(List<GeneratedChain> tree, String uuid) {
        return findChain(tree, uuid).lastSeq();
    }

    private static GeneratedChain findChain(List<GeneratedChain> tree, String uuid) {
        for (GeneratedChain chain : tree) {
            if (chain.uuid()
                .equals(uuid)) {
                return chain;
            }
        }
        throw new IllegalArgumentException("no chain " + uuid + " in the generated tree");
    }

    private static Set<String> readUuids(List<GeneratedChain> tree) {
        final Set<String> uuids = new HashSet<>();
        for (GeneratedChain chain : tree) {
            uuids.add(chain.uuid());
        }
        return uuids;
    }

    private static String formatLine(long seq, String type, JsonObject data) {
        final JsonObject line = new JsonObject();
        line.addProperty("v", 1);
        line.addProperty("seq", seq);
        line.addProperty("sid", "session");
        line.addProperty("sidx", 1);
        line.addProperty("t", type);
        line.addProperty("wall", 0);
        line.addProperty("ticks", 0);
        line.addProperty("pticks", 0);
        line.addProperty("thread", "Server thread");
        line.add("data", data);
        line.addProperty("prev", "0");
        return JsonUtil.GSON.toJson(line);
    }

    private record GeneratedChain(String uuid, @Nullable String parentUuid, long lastSeq) {}
}
