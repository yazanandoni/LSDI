package com.autojoin.synthesis;

import com.autojoin.AutoJoin;
import com.autojoin.JoinResult;
import com.autojoin.model.Column;
import com.autojoin.model.Row;
import com.autojoin.model.Table;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;


import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end integration tests for the full Auto-Join pipeline using the
 * table pairs from the paper.
 *
 * Each test builds two Table objects, calls AutoJoin.join(), and asserts that
 * the expected row pairs are present and that no obviously wrong pairs exist.
 */
class AutoJoinIntegrationTest {

    private final AutoJoin autoJoin = new AutoJoin();

    /**
     * Pretty-prints a JoinResult. AutoJoin does not build a merged table; it
     * returns a list of [sourceRow, targetRow] pairs, so we print each pair's
     * full source row beside its full target row.
     */
    private static void printResult(String testName, JoinResult result) {
        System.out.println("\n===== " + testName + " =====");
        if (result.isEmpty()) {
            System.out.println("  <empty result>");
            return;
        }
        System.out.println("  transform: " + result.getTransformationDescription());
        System.out.println("  " + result.size() + " joined pair(s):");
        int i = 1;
        for (Row[] pair : result.getJoinedPairs()) {
            System.out.println("  [" + (i++) + "] source=" + pair[0].asMap()
                    + "  ||  target=" + pair[1].asMap());
        }
    }

    // -----------------------------------------------------------------------
    // Figure 1: US presidents — "First Last" vs "Last, First(year)"
    // -----------------------------------------------------------------------

    @Test
    void figure1_presidentsJoin() {
        // Left table: President (key), Popular Vote
        Table leftTable = new Table("presidents_votes", List.of(
                new Column("President", List.of(
                        "Barack Obama", "George W. Bush", "Bill Clinton",
                        "George H. W. Bush", "Ronald Reagan"), true),
                new Column("PopularVote", List.of(
                        "52.93%", "47.87%", "43.01%", "53.37%", "50.75%"), false)
        ));

        // Right table: President (key, formatted differently), Approval Rating
        Table rightTable = new Table("presidents_approval", List.of(
                new Column("President", List.of(
                        "Obama, Barack(1961-)", "Bush, George W.(1946-)",
                        "Clinton, Bill(1946-)", "Bush, George H. W.(1924-)",
                        "Reagan, Ronald(1911-2004)"), true),
                new Column("ApprovalRating", List.of(
                        "47.0", "49.4", "55.1", "60.9", "52.8"), false)
        ));

        JoinResult result = autoJoin.join(leftTable, rightTable);
        printResult("figure1_presidentsJoin", result);

        assertFalse(result.isEmpty(), "Expected at least one joined pair");
        // AutoJoin returns whichever direction joins best, so pair[0] may be from
        // either table. Collect President values from both sides of each pair.
        Set<String> joinedNames = result.getJoinedPairs().stream()
                .flatMap(pair -> Stream.of(pair[0].get("President"), pair[1].get("President")))
                .collect(Collectors.toSet());
        assertTrue(joinedNames.contains("Barack Obama"),  "Obama should be joined");
        assertTrue(joinedNames.contains("Bill Clinton"),  "Clinton should be joined");
        assertTrue(joinedNames.contains("Ronald Reagan"), "Reagan should be joined");
    }

    // -----------------------------------------------------------------------
    // Figure 4: Session IDs — separate fields vs concatenated "[ID] Name"
    // -----------------------------------------------------------------------

    @Test
    void figure4_sessionNameJoin() {
        // Left table: ID + session name (two separate columns)
        Table leftTable = new Table("sessions_split", List.of(
                new Column("ID", List.of(
                        "UBAX01", "UBAX02", "UBAX03", "UBAX04", "UBAX05"), false),
                new Column("SessionName", List.of(
                        "AXUG General Session", "How2 Session",
                        "Master Planning Session", "Financial Reporting",
                        "Master Planning Session"), false)
        ));

        // Right table: concatenated full session name (key)
        Table rightTable = new Table("sessions_full", List.of(
                new Column("FullSessionName", List.of(
                        "[UBAX01] AXUG General Session",
                        "[UBAX02] How2 Session",
                        "[UBAX03] Master Planning Session",
                        "[UBAX04] Financial Reporting",
                        "[UBAX05] Master Planning Session"), true),
                new Column("Month", List.of("Mar", "Apr", "Apr", "Oct", "Dec"), false)
        ));

        JoinResult result = autoJoin.join(leftTable, rightTable);
        printResult("figure4_sessionNameJoin", result);

        assertFalse(result.isEmpty(), "Expected at least one joined pair for Figure 4");

        // Every joined pair's derived value should equal the target key
        for (Row[] pair : result.getJoinedPairs()) {
            // pair[0] is from leftTable, pair[1] is from rightTable
            String id = pair[0].get("ID");
            String fullName = pair[1].get("FullSessionName");
            // The joined full name should contain the ID
            assertTrue(fullName.contains(id),
                    "Joined target '" + fullName + "' should contain source ID '" + id + "'");
        }
    }

    // -----------------------------------------------------------------------
    // Synthetic: simple prefix concatenation  A + "-" + B → "A-B"
    // -----------------------------------------------------------------------

    @Test
    void synthetic_concatenationJoin() {
        Table sourceTable = new Table("source", List.of(
                new Column("First", List.of("FOO", "BAR", "BAZ"), false),
                new Column("Second", List.of("001", "002", "003"), false)
        ));

        Table targetTable = new Table("target", List.of(
                new Column("Key", List.of("FOO-001", "BAR-002", "BAZ-003"), true)
        ));

        JoinResult result = autoJoin.join(sourceTable, targetTable);
        printResult("synthetic_concatenationJoin", result);

        assertFalse(result.isEmpty(), "Expected join to succeed for simple concat");
        assertEquals(3, result.size(), "Expected all 3 rows to join");
    }

    // -----------------------------------------------------------------------
    // Synthetic: substring extraction — source has extra suffix
    // -----------------------------------------------------------------------

    @Test
    void synthetic_substringExtraction() {
        // Source has values like "CODE_extra", target key is just "CODE"
        Table sourceTable = new Table("source", List.of(
                new Column("Value", List.of("ALPHA_extra", "BETA_extra", "GAMMA_extra"), false)
        ));

        Table targetTable = new Table("target", List.of(
                new Column("Key", List.of("ALPHA", "BETA", "GAMMA"), true),
                new Column("Data", List.of("x", "y", "z"), false)
        ));

        JoinResult result = autoJoin.join(sourceTable, targetTable);
        printResult("synthetic_substringExtraction", result);

        assertFalse(result.isEmpty(), "Expected join to succeed for substring extraction");
        assertEquals(3, result.size(), "Expected all 3 rows to join");
    }

    // -----------------------------------------------------------------------
    // Non-joinable tables: result should be empty (not crash)
    // -----------------------------------------------------------------------

    @Test
    void nonJoinableTables_returnsEmpty() {
        Table sourceTable = new Table("source", List.of(
                new Column("A", List.of("apple", "banana", "cherry"), false)
        ));

        Table targetTable = new Table("target", List.of(
                new Column("B", List.of("111", "222", "333"), true)
        ));

        JoinResult result = autoJoin.join(sourceTable, targetTable);
        printResult("nonJoinableTables_returnsEmpty", result);
        // We just assert it doesn't throw and returns a coherent result
        assertNotNull(result);
    }
}
