package com.gtnhspeedrun.audit.export;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import com.gtnhspeedrun.audit.core.Verifier;

/**
 * MILESTONES has one QUEST line per quest id. The line is at the quest's first completion and has the count of distinct
 * players who completed the quest.
 */
class RunSummaryQuestTest {

    private static final String[] PLAYERS = { "p1", "p2", "p3", "p4", "p5", "p6" };
    // matches:
    // `2026-11-09 07:19:30 UTC [0h15m45s igt] QUEST Quest q3 (2 players)`
    // `... QUEST Quest q0 (1 player)`
    private static final Pattern QUEST_LINE = Pattern.compile("QUEST {6}Quest (\\S+) \\((\\d+) players?\\)$");

    @Test
    void generatedPartiesGetOneLinePerQuest() {
        final int ROUNDS = 20;
        final int MAX_QUESTS = 8;
        final int MAX_COMPLETIONS = 12;
        final Random random = new Random(1027L);
        for (int round = 0; round < ROUNDS; round++) {
            final List<String[]> completions = new ArrayList<>();
            final int quests = 1 + random.nextInt(MAX_QUESTS);
            final int count = random.nextInt(MAX_COMPLETIONS + 1);
            for (int completion = 0; completion < count; completion++) {
                completions.add(new String[] { "q" + random.nextInt(quests), PLAYERS[random.nextInt(PLAYERS.length)] });
            }
            checkQuestLines(completions);
        }
    }

    @Test
    void boundaryCasesOfOnePlayerAndARepeatedPlayer() {
        checkQuestLines(List.of());
        checkQuestLines(List.<String[]>of(new String[] { "q0", "p1" }));
        checkQuestLines(List.of(new String[] { "q0", "p1" }, new String[] { "q0", "p1" }, new String[] { "q0", "p2" }));
    }

    /** The completions are { quest id, player uuid } pairs in log order. */
    private static void checkQuestLines(List<String[]> completions) {
        final List<JsonObject> lines = new ArrayList<>();
        final Map<String, Set<String>> expectedPlayers = new LinkedHashMap<>();
        for (int seq = 0; seq < completions.size(); seq++) {
            final String questId = completions.get(seq)[0];
            final String player = completions.get(seq)[1];
            final JsonObject data = new JsonObject();
            data.addProperty("playerUuid", player);
            data.addProperty("questId", questId);
            data.addProperty("questName", "Quest " + questId);
            final JsonObject line = new JsonObject();
            line.addProperty("seq", seq);
            line.addProperty("sidx", 1);
            line.addProperty("t", "quest_complete");
            line.addProperty("wall", 1_000_000L + 1000L * seq);
            line.addProperty("ticks", 20L * seq);
            line.add("data", data);
            lines.add(line);
            expectedPlayers.computeIfAbsent(questId, unused -> new HashSet<>())
                .add(player);
        }

        final String summary = RunSummary.formatSummary(
            new RunSummary.SummaryInput(
                lines,
                new Verifier.Result("OK", lines.size(), 0, lines.size() - 1L, "", List.of()),
                new AuditExport.AnchorView("world", lines.size() - 1L, 0, 0, 0),
                List.of()));

        final Map<String, Integer> shownPlayers = new LinkedHashMap<>();
        for (String summaryLine : summary.split("\n")) {
            final Matcher quest = QUEST_LINE.matcher(summaryLine);
            if (quest.find()) {
                assertEquals(null, shownPlayers.put(quest.group(1), Integer.parseInt(quest.group(2))), quest.group(1));
            }
        }
        final Map<String, Integer> expectedCounts = new LinkedHashMap<>();
        for (Map.Entry<String, Set<String>> quest : expectedPlayers.entrySet()) {
            expectedCounts.put(
                quest.getKey(),
                quest.getValue()
                    .size());
        }
        assertEquals(new ArrayList<>(expectedCounts.entrySet()), new ArrayList<>(shownPlayers.entrySet()));
    }
}
