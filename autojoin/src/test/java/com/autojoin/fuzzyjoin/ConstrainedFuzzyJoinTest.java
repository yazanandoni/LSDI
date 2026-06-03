package com.autojoin.fuzzyjoin;

import com.autojoin.fuzzy_join.ConstrainedFuzzyJoin;
import com.autojoin.fuzzy_join.FuzzyJoinResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ConstrainedFuzzyJoinTest {

    private ConstrainedFuzzyJoin fuzzyJoin;

    @BeforeEach
    void setUp() {
        fuzzyJoin = new ConstrainedFuzzyJoin();
    }

    @Test
    void testPaperExample9_ExactDistanceMath() {
        // The paper explicitly states:
        // Source: mpayne@forsyth.k12.ga.us
        // Target: mipayne@forsyth.k12.ga.us
        // Distance using 3-gram Jaccard should be exactly 0.125
        List<String> sourceC = List.of("mpayne@forsyth.k12.ga.us");
        List<String> targetK = List.of("mipayne@forsyth.k12.ga.us");

        List<FuzzyJoinResult> results = fuzzyJoin.executeJoin(sourceC, targetK);

        assertEquals(1, results.size(), "Should find exactly 1 match");

        // Assert the Jaccard distance math matches the paper perfectly
        double expectedDistance = 0.125;
        assertEquals(expectedDistance, results.get(0).distance, 0.001,
                "The 3-gram Jaccard distance should match Example 9");
    }

    @Test
    void testConstraint1_PreventsOneSourceToMultipleTargets() {
        //"sales" is the transformed source.
        // It perfectly matches target 0 ("sales"), but is also very close to target 1 ("sales dept").
        List<String> sourceC = List.of("sales");
        List<String> targetK = Arrays.asList("sales", "sales dept");

        List<FuzzyJoinResult> results = fuzzyJoin.executeJoin(sourceC, targetK);

        // If the constraints weren't working, "sales" might match both target keys.
        // The binary search should have shrunk the threshold to prevent this collision.
        assertEquals(1, results.size(), "Constraint 1 should limit the source to 1 target match");
        assertEquals(0, results.get(0).targetRowIndex, "Should only match the exact 'sales' target");
        assertEquals(0.0, results.get(0).distance, 0.001);
    }

    @Test
    void testConstraint2_PreventsMultipleDistinctSourcesToOneTarget() {
        // Based on the logic of Example 9 from the paper where "moore" shouldn't join "mipayne".
        // Two highly similar distinct source strings competing for the SAME target key.
        List<String> sourceC = Arrays.asList("George Bush", "George W. Bush");
        List<String> targetK = List.of("Bush, George W.");

        List<FuzzyJoinResult> results = fuzzyJoin.executeJoin(sourceC, targetK);

        // If Constraint 2 is ACTIVE, the algorithm assumes these two distinct strings
        // represent different entities. It will lower the threshold so that at most ONE of them matches.
        // If it allowed both, target index 0 would join with two distinct source strings.
        assertTrue(results.size() <= 1,
                "Constraint 2 should prevent multiple distinct sources from merging into 1 key");
    }
}
