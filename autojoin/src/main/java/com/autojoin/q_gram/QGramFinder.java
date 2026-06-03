package com.autojoin.q_gram;

import java.util.ArrayList;
import java.util.List;

public class QGramFinder {

    /**
     * Minimum q-gram length to consider (Appendix E.1 / Algorithm 3:
     * BinarySearchQ starts at a ← 3 and matches with q* &lt; 3 are skipped).
     * Searching only prefixes of length ≥ 3 ensures the optimal q-gram chosen
     * for a value is itself ≥ 3 — a shorter, likely-coincidental prefix cannot
     * win the per-value selection and then be discarded, which would lose a
     * valid longer match for that value.
     */
    private static final int MIN_QGRAM_LENGTH = 3;

    public MatchResult findOptimalQGram(String v, ColumnSuffixIndex sourceIdx, ColumnSuffixIndex targetIdx) {
        String bestQGram = null;
        double bestScore = -1.0;
        int bestN = 0;
        int bestM = 0;
        List<Integer> bestSourceRows = new ArrayList<>();
        List<Integer> bestTargetRows = new ArrayList<>();

        // Iterate through all suffixes of 'v'
        for (int i = 0; i < v.length(); i++) {
            String suffix = v.substring(i);

            // Find the longest prefix of this suffix that still occurs in the
            // target, via binary search (Algorithm 3, BinarySearchQ). By the
            // monotonicity of Proposition 2 the match count is non-increasing in
            // prefix length, so the set of lengths that still match is a
            // contiguous prefix of [MIN_QGRAM_LENGTH .. |suffix|] — binary search
            // finds its upper boundary in O(log|suffix|) index probes instead of
            // the O(|suffix|) linear scan.
            int q = binarySearchLongestMatch(suffix, targetIdx);
            if (q < MIN_QGRAM_LENGTH) continue; // no match of length >= 3 (line 9: q* < 3)

            String longestMatchingPrefix = suffix.substring(0, q);
            List<Integer> targetMatchesForPrefix = targetIdx.findMatches(longestMatchingPrefix);

            // Score it and compare against the GLOBAL best.
            {
                List<Integer> sourceMatches = sourceIdx.findMatches(longestMatchingPrefix);
                int n = sourceMatches.size();
                int m = targetMatchesForPrefix.size();

                double score = 1.0 / (n * m); // score for this specific suffix

                // Is this suffix's best prefix better than the overall winner?
                if (score > bestScore) {
                    bestScore = score;
                    bestN = n;
                    bestM = m;
                    bestQGram = longestMatchingPrefix;
                    bestSourceRows = sourceMatches;
                    bestTargetRows = targetMatchesForPrefix;
                }
            }
        }

        return new MatchResult(bestQGram, bestScore, bestN, bestM, bestSourceRows, bestTargetRows);
    }

    /**
     * Binary search for the length of the longest prefix of {@code suffix} that
     * still occurs in the target column (Algorithm 3, BinarySearchQ). Returns the
     * largest length in [MIN_QGRAM_LENGTH, |suffix|] whose prefix has a match, or
     * a value &lt; MIN_QGRAM_LENGTH when no prefix of length ≥ MIN_QGRAM_LENGTH
     * matches. Correctness relies on Proposition 2: the match count is
     * monotonically non-increasing as the prefix grows.
     */
    private static int binarySearchLongestMatch(String suffix, ColumnSuffixIndex targetIdx) {
        int a = MIN_QGRAM_LENGTH;       // smallest length we consider (paper: a ← 3)
        int b = suffix.length() + 1;    // exclusive upper bound (paper: b ← Length(u)+1)
        while (a < b) {
            int h = a + (b - a) / 2;
            if (!targetIdx.findMatches(suffix.substring(0, h)).isEmpty()) {
                a = h + 1;              // length h matches → try longer
            } else {
                b = h;                  // length h has no match → must be shorter
            }
        }
        return a - 1;                   // largest length with a match
    }
}
