package com.autojoin.q_gram;

import java.util.ArrayList;
import java.util.List;

public class QGramFinder {
    public MatchResult findOptimalQGram(String v, ColumnSuffixIndex sourceIdx, ColumnSuffixIndex targetIdx) {
        String bestQGram = null;
        double bestScore = -1.0;
        List<Integer> bestSourceRows = new ArrayList<>();
        List<Integer> bestTargetRows = new ArrayList<>();

        // Iterate through all suffixes of 'v'
        for (int i = 0; i < v.length(); i++) {
            String suffix = v.substring(i);

            String longestMatchingPrefix = null;
            List<Integer> targetMatchesForPrefix = null;

            // Find the longest prefix for the current suffix that exists in the target
            for (int len = suffix.length(); len >= 1; len--) {
                String prefix = suffix.substring(0, len);
                List<Integer> targetMatches = targetIdx.findMatches(prefix);

                if (!targetMatches.isEmpty()) {
                    longestMatchingPrefix = prefix;
                    targetMatchesForPrefix = targetMatches;
                    // loop starts with the full length of the suffix as prefix and decreases with every iteration
                    // if a match is found, the loop breaks since the next prefix would be shorter, and we want to have the longest prefix that fits
                    break;
                }
            }

            // If a match was found, score it and compare against the GLOBAL best
            if (longestMatchingPrefix != null) {
                List<Integer> sourceMatches = sourceIdx.findMatches(longestMatchingPrefix);
                int n = sourceMatches.size();
                int m = targetMatchesForPrefix.size();

                double score = 1.0 / (n * m); // score for this specific suffix

                // Is this suffix's best prefix better than the overall winner?
                if (score > bestScore) {
                    bestScore = score;
                    bestQGram = longestMatchingPrefix;
                    bestSourceRows = sourceMatches;
                    bestTargetRows = targetMatchesForPrefix;
                }
            }
        }

        return new MatchResult(bestQGram, bestScore, bestSourceRows, bestTargetRows);
    }
}
