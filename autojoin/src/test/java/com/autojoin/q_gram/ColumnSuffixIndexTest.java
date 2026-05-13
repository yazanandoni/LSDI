package com.autojoin.q_gram;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.Arrays;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ColumnSuffixIndexTest {

    private ColumnSuffixIndex sourceIndex;

    @BeforeEach
    void setUp() {
        // Data from Figure 1: Left Table (Source)
        List<String> sourceData = Arrays.asList(
                "Barack Obama",         // Index 0
                "George W. Bush",       // Index 1
                "Bill Clinton",         // Index 2
                "George H. W. Bush",    // Index 3
                "Ronald Reagan"         // Index 4
        );
        sourceIndex = new ColumnSuffixIndex(sourceData);
    }

    @Test
    void testFindMatches_ExactPrefix() {
        // "Barack" should only match row 0
        List<Integer> matches = sourceIndex.findMatches("Barack");
        assertEquals(1, matches.size());
        assertTrue(matches.contains(0));
    }

    @Test
    void testFindMatches_MultiplePrefixes() {
        // "George " should match both "George W. Bush" (row 1) and "George H. W. Bush" (row 3)
        List<Integer> matches = sourceIndex.findMatches("George ");
        assertEquals(2, matches.size());
        assertTrue(matches.contains(1));
        assertTrue(matches.contains(3));
    }

    @Test
    void testFindMatches_NoMatch() {
        // "Lincoln" does not exist in this sample
        List<Integer> matches = sourceIndex.findMatches("Lincoln");
        assertTrue(matches.isEmpty());
    }
}