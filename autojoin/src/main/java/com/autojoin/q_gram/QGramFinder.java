package com.autojoin.q_gram;

import java.util.ArrayList;
import java.util.List;

public class QGramFinder {

    private static final int MIN_QGRAM_LENGTH = 3;

    public MatchResult findOptimalQGram(String v, ColumnSuffixIndex sourceIdx, ColumnSuffixIndex targetIdx) {
        String bestQGram = null;
        double bestScore = -1.0;
        int bestN = 0;
        int bestM = 0;

        for (int i = 0; i < v.length(); i++) {
            String suffix = v.substring(i);

            int q = binarySearchLongestMatch(suffix, targetIdx);
            if (q < MIN_QGRAM_LENGTH) continue; // no match of length >= 3 (line 9: q* < 3)

            String longestMatchingPrefix = suffix.substring(0, q);

            int n = sourceIdx.countMatches(longestMatchingPrefix);
            int m = targetIdx.countMatches(longestMatchingPrefix);

            double score = 1.0 / (n * m);

            if (score > bestScore) {
                bestScore = score;
                bestN = n;
                bestM = m;
                bestQGram = longestMatchingPrefix;
            }
        }

        List<Integer> bestSourceRows = bestQGram == null
                ? new ArrayList<>() : sourceIdx.findMatches(bestQGram);
        List<Integer> bestTargetRows = bestQGram == null
                ? new ArrayList<>() : targetIdx.findMatches(bestQGram);

        return new MatchResult(bestQGram, bestScore, bestN, bestM, bestSourceRows, bestTargetRows);
    }

    private static int binarySearchLongestMatch(String suffix, ColumnSuffixIndex targetIdx) {
        int a = MIN_QGRAM_LENGTH;
        int b = suffix.length() + 1;
        while (a < b) {
            int h = a + (b - a) / 2;
            if (targetIdx.hasMatch(suffix.substring(0, h))) {
                a = h + 1;
            } else {
                b = h;
            }
        }
        return a - 1;
    }
}
