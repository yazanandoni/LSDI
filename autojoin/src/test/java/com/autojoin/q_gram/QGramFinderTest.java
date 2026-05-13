package com.autojoin.q_gram;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.Arrays;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class QGramFinderTest {

    private ColumnSuffixIndex sourceIndex;
    private ColumnSuffixIndex targetIndex;
    private QGramFinder matcher;

    @BeforeEach
    void setUp() {
        // Data from Figure 1: Left Table
        List<String> sourceData = Arrays.asList(
                "Barack Obama",
                "George W. Bush",
                "Bill Clinton",
                "George H. W. Bush",
                "Ronald Reagan"
        );

        // Data from Figure 1: Right Table (Target)
        List<String> targetData = Arrays.asList(
                "Obama, Barack(1961-)",
                "Bush, George W.(1946-)",
                "Clinton, Bill (1946-)",
                "Bush, George H. W.(1924-)",
                "Reagan, Ronald (1911-2004)"
        );

        sourceIndex = new ColumnSuffixIndex(sourceData);
        targetIndex = new ColumnSuffixIndex(targetData);
        matcher = new QGramFinder();
    }

    @Test
    void testFindOptimalQGram_BarackObama() {
        // The paper states: "the 6-gram Barack appears only once in both tables"
        MatchResult result = matcher.findOptimalQGram("Barack Obama", sourceIndex, targetIndex);

        assertNotNull(result);
        assertEquals("Barack", result.qGram);

        // It should be a perfect 1-to-1 match, so the score must be 1.0 / (1 * 1) = 1.0
        assertEquals(1.0, result.score, 0.0001);

        // It should link row 0 of the source to row 0 of the target
        assertEquals(1, result.bestSourceRows.size());
        assertEquals(0, result.bestSourceRows.get(0));

        assertEquals(1, result.bestTargetRows.size());
        assertEquals(0, result.bestTargetRows.get(0));
    }

    @Test
    void testFindOptimalQGram_GeorgeWBush() {
        // The paper states: "the prefix 'George W.' for the first suffix is the best g*"
        MatchResult result = matcher.findOptimalQGram("George W. Bush", sourceIndex, targetIndex);

        assertNotNull(result);
        assertEquals("George W.", result.qGram);

        // It should be a 1-to-1 match (score 1.0) because "George W." uniquely identifies him,
        // whereas just "George" would result in a 2-to-2 match (score 0.25).
        assertEquals(1.0, result.score, 0.0001);

        // It should link row 1 of the source to row 1 of the target
        assertTrue(result.bestSourceRows.contains(1));
        assertTrue(result.bestTargetRows.contains(1));
    }
}
