package com.autojoin.q_gram;

import com.autojoin.model.Column;
import com.autojoin.model.Table;

import java.util.*;

public class AutoJoinDiscovery {

    /**
     * Maximum n·m a q-gram match may have to be treated as evidence of a
     * joinable column pair. The paper's joinable-row-pair signal is the 1-to-1
     * q-gram match (n=m=1, Definition 2 / §3.1.1); n-to-m matches generalise it
     * with "goodness" 1/(n·m). Keeping every match with score>0 lets clearly
     * non-joinable column pairs (e.g. a song title vs a multi-word
     * "Songwriter(s)" value) contribute hundreds of junk matches that are then
     * fed to the expensive transformation learner. We keep only matches that are
     * sufficiently unique; bad column pairs then yield no matches and are
     * skipped. The cap is generous enough to retain real n-to-m joins (e.g. a
     * value repeated across a handful of rows).
     */
    private static final int MAX_MATCH_PRODUCT = 1;

    /**
     * Minimum length of the matching q-gram, per the paper (Appendix E.1,
     * Algorithm 3 line 9 "if q* < 3 ... continue", and BinarySearchQ starting at
     * a ← 3). The paper states: "when q < 3, the number of q-gram matches can be
     * very large, severely impacting performance. Thus, we force q and the
     * minimum length of suffixes to be at least 3." A 1-2 character q-gram is
     * also (Proposition 1) very likely a chance coincidence rather than a true
     * joinable-row signal, so dropping these removes noise from the example pool.
     */
    private static final int MIN_QGRAM_LENGTH = 3;

    public List<ColumnPairMatches> findJoinableRowPairs(Table sourceTable, Table targetTable) {
        Map<String, List<MatchResult>> groupedMatches = new HashMap<>();

        // Iterate over all columns in the source table
        for (Column cs : sourceTable.getColumns()) {
            ColumnSuffixIndex sourceIdx = new ColumnSuffixIndex(cs.getValues());

            // Iterate over all KEY columns in the target table
            for (Column ct : targetTable.getKeyColumns()) {
                ColumnSuffixIndex targetIdx = new ColumnSuffixIndex(ct.getValues());

                String groupKey = cs.getName() + "|" + ct.getName();
                groupedMatches.putIfAbsent(groupKey, new ArrayList<>());

                // Iterate over DISTINCT values in the source column
                Set<String> distinctValues = new HashSet<>(cs.getValues());
                for (String v : distinctValues) {
                    if (v == null || v.isEmpty()) continue;

                    QGramFinder matcher = new QGramFinder();
                    MatchResult result = matcher.findOptimalQGram(v, sourceIdx, targetIdx);

                    // Keep only sufficiently-unique matches (low n·m). This is
                    // the paper's joinable-row-pair quality signal; it stops
                    // non-joinable column pairs from flooding the learner.
                    if (result != null && result.getQGram() != null
                            && result.getQGram().length() >= MIN_QGRAM_LENGTH
                            && result.getMatchProduct() <= MAX_MATCH_PRODUCT) {
                        groupedMatches.get(groupKey).add(result);
                    }
                }
            }
        }

        // Sort the matches in each group highest-to-lowest
        List<ColumnPairMatches> finalResults = new ArrayList<>();
        for (Map.Entry<String, List<MatchResult>> entry : groupedMatches.entrySet()) {
            List<MatchResult> matches = entry.getValue();

            // Sort by goodness (1/n·m) descending, then — among equally-good
            // matches — by q-gram length descending. Proposition 1 (§3.1.1)
            // shows a 1-to-1 q-gram match on a LONGER q-gram is exponentially
            // less likely to be a chance coincidence, so it is far more likely
            // to be a true joinable row pair. Every kept match here is 1-to-1
            // (n·m = 1) and thus ties on goodness, so this length tie-break
            // front-loads the most trustworthy pairs into the top-k example pool
            // and pushes coincidental short-q-gram pairings (e.g. unrelated
            // songs sharing "Christmas") down. A generous top-k (see
            // TransformationLearner) keeps enough lower-ranked diversity that
            // transform-requiring pairs (e.g. Beatles title-casing, whose common
            // q-gram is shorter than an exact match) still get sampled.
            matches.sort((a, b) -> {
                int byScore = Double.compare(b.score, a.score);
                if (byScore != 0) return byScore;
                int lenA = a.getQGram() == null ? 0 : a.getQGram().length();
                int lenB = b.getQGram() == null ? 0 : b.getQGram().length();
                return Integer.compare(lenB, lenA);
            });

            String[] cols = entry.getKey().split("\\|");
            ColumnPairMatches group = new ColumnPairMatches();
            group.sourceColumnName = cols[0];
            group.targetColumnName = cols[1];
            group.matches = matches;

            finalResults.add(group);
        }

        return finalResults;
    }
}
