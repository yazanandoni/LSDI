package com.autojoin.q_gram;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
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

    /**
     * Differential test against the previous implementation, which
     * materialised every suffix as a copied String and stable-sorted them.
     * The offset-based index must return byte-identical results — same rows,
     * same order, same counts — on data with nulls, empties and heavy
     * duplicate suffixes (small alphabet).
     */
    @Test
    void matchesOldMaterializedImplementation() {
        Random rnd = new Random(42);
        String alphabet = "abcd ";
        List<String> data = new ArrayList<>();
        for (int i = 0; i < 300; i++) {
            if (rnd.nextInt(20) == 0) {
                data.add(rnd.nextBoolean() ? null : "");
                continue;
            }
            StringBuilder sb = new StringBuilder();
            int len = rnd.nextInt(25);
            for (int j = 0; j < len; j++) {
                sb.append(alphabet.charAt(rnd.nextInt(alphabet.length())));
            }
            data.add(sb.toString());
        }
        ColumnSuffixIndex index = new ColumnSuffixIndex(data);

        record Ref(String text, int row) {}
        List<Ref> ref = new ArrayList<>();
        for (int row = 0; row < data.size(); row++) {
            String v = data.get(row);
            if (v == null) continue;
            for (int i = 0; i < v.length(); i++) {
                ref.add(new Ref(v.substring(i), row));
            }
        }
        ref.sort(Comparator.comparing(Ref::text));

        List<String> qGrams = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            StringBuilder sb = new StringBuilder();
            int len = 1 + rnd.nextInt(8);
            for (int j = 0; j < len; j++) {
                sb.append(alphabet.charAt(rnd.nextInt(alphabet.length())));
            }
            qGrams.add(sb.toString());
        }
        for (String v : data) {
            if (v != null && v.length() >= 4) {
                int start = v.length() / 3;
                qGrams.add(v.substring(start, Math.min(v.length(), start + 4)));
            }
        }
        qGrams.add("zzz");

        for (String q : qGrams) {
            List<Integer> expected = ref.stream()
                    .filter(r -> r.text().startsWith(q)).map(Ref::row).toList();
            assertEquals(expected, index.findMatches(q), "findMatches(" + q + ")");
            assertEquals(!expected.isEmpty(), index.hasMatch(q), "hasMatch(" + q + ")");
            assertEquals(expected.size(), index.countMatches(q), "countMatches(" + q + ")");
        }
    }
}