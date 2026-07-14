package com.autojoin.fuzzy_join;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;

public class ConstrainedFuzzyJoin {

    /**
     * Holds the result of {@link #recoverUnmatched} together with the optimal
     * threshold that was used, so callers can surface both in trace output.
     */
    public static final class RecoveryOutcome {
        private final List<FuzzyJoinResult> results;
        private final double optimalThreshold;

        public RecoveryOutcome(List<FuzzyJoinResult> results, double optimalThreshold) {
            this.results = results;
            this.optimalThreshold = optimalThreshold;
        }

        public List<FuzzyJoinResult> getResults() { return results; }
        public double getOptimalThreshold() { return optimalThreshold; }
    }
    private static final double EPSILON = 0.001; // Epsilon for comparison in binary search
    private static final int q = 3; // size of q_gram used for distance calculation

    /**
     * Cooperative cancellation: the backend runs the FJ-C baseline under a
     * paper-style timeout (§6.4) and interrupts on expiry. A no-op on threads
     * that are never interrupted (e.g. Auto-Join's own fuzzy-recovery pass).
     */
    private static void checkCancelled() {
        if (Thread.currentThread().isInterrupted())
            throw new CancellationException("fuzzy join cancelled (timeout)");
    }

    /**
     * Tokenization scheme for the distance metric. The paper optimizes the fuzzy
     * join over a space of tokenizations (§6.2 lists Exact/Lower/Split/Word/q-gram)
     * jointly with the threshold — Eq. 10's argmax over (t, d, s). We sweep two:
     *
     *  - QGRAM: overlapping 3-grams. Good for intra-token noise (typos, missing
     *    letters, formatting) — e.g. the paper's mpayne/mipayne email example.
     *  - WORD:  whitespace-delimited tokens. Good for token-level differences:
     *    reordering ("Agarwal Pankaj K." vs "Pankaj K. Agarwal" → identical word
     *    set, distance 0) or extra/missing words ("Earl of Wilmington" vs "Spencer
     *    Compton Earl of Wilmington"). With 3-grams these same pairs look far while
     *    unrelated values sharing a numeric/word prefix look close, which collapses
     *    the cardinality-constrained threshold to ~0 and disables recovery.
     */
    private enum Tokenization { QGRAM, QGRAM2, WORD }

    /**
     * Eq. 10 sweep space for the standalone FJ-C/FJ-FR baselines (paper §5:
     * "a tokenziation scheme t from a space of possible configurations (e.g.,
     * word, 2-gram, 3-gram, etc.)"). Auto-Join's own §5 recovery pass keeps
     * its established QGRAM+WORD sweep (see recoverUnmatched) — the validated
     * AJ web numbers depend on it, and 2-grams mostly matter for the short
     * noisy values the baselines meet when joining RAW columns.
     */
    private static final List<Tokenization> BASELINE_SWEEP =
            List.of(Tokenization.QGRAM, Tokenization.QGRAM2, Tokenization.WORD);
    private static final List<Tokenization> RECOVERY_SWEEP =
            List.of(Tokenization.QGRAM, Tokenization.WORD);

    /**
     * Cap on the cached pairwise distance matrix (distinct-value pairs). The
     * binary search probes ~log2(1/EPSILON) thresholds and each probe needs the
     * same distances, so caching them below this size avoids recomputing the
     * Jaccard overlap ~10 times per pair. Above the cap distances are
     * recomputed per probe from the (always cached) q-gram token sets.
     */
    private static final long MAX_MATRIX_ENTRIES = 4_000_000L;

    /**
     * Safety cap on distinct-value pair count for the recovery pass. The
     * threshold optimization is inherently all-pairs over distinct values;
     * beyond this size it would no longer be interactive, so recovery is
     * skipped rather than stalling the join.
     */
    private static final long MAX_RECOVERY_PAIRS = 25_000_000L;

    /**
     * Executes the constrained fuzzy join and returns the joined row pairs.
     *
     * @param transformedSourceColumn The source column AFTER transformation (C)
     * @param targetKeyColumn         The original target key column (K)
     * @return A list of successfully joined row pairs
     */
    public List<FuzzyJoinResult> executeJoin(List<String> transformedSourceColumn, List<String> targetKeyColumn) {
        // Paper sec. 6.2 on the fuzzy-join baselines: "we join each row with
        // top-1 fuzzy match in the other table to maintain high precision".
        // Per tokenization (Eq. 10's t) each distinct source value gets its
        // single closest target; the threshold is then tuned as loose as the
        // cardinality constraints allow ON THE EMITTED MATCHES (each source
        // row joins at most one target — structural under top-1 — and no
        // target value is claimed by two distinct source values). Among the
        // tokenizations, the one covering the most target rows wins (Eq. 9).
        // A GLOBAL all-pairs constraint check would instead collapse the
        // threshold whenever any source value merely sits near two targets
        // (multi-term entities, similar usernames), joining almost nothing.
        List<FuzzyJoinResult> best = List.of();
        int bestCoverage = 0;
        for (Tokenization tok : BASELINE_SWEEP) {
            DistanceModel model = new DistanceModel(transformedSourceColumn, targetKeyColumn, tok);
            int[] bestKi = model.closestTargets();
            double threshold = model.topOneThreshold(bestKi);

            List<FuzzyJoinResult> joinedRows = new ArrayList<>();
            Set<Integer> coveredTargets = new HashSet<>();
            int[] firstRow = model.firstRowOfTargetValue(targetKeyColumn.size());
            for (int i = 0; i < transformedSourceColumn.size(); i++) {
                int ci = model.sourceValueIdx[i];
                if (ci < 0 || bestKi[ci] < 0) continue;
                double d = model.distance(ci, bestKi[ci]);
                if (d <= threshold) {
                    int j = firstRow[bestKi[ci]];
                    joinedRows.add(new FuzzyJoinResult(i, j, d));
                    coveredTargets.add(j);
                }
            }
            if (coveredTargets.size() > bestCoverage) {
                bestCoverage = coveredTargets.size();
                best = joinedRows;
            }
        }
        return best;
    }

    /**
     * Recovery pass for rows the transformation equi-join left unmatched
     * (paper §5): the distance threshold is optimized over the FULL columns —
     * so values that already equi-join act as constraints and cannot gain
     * extra matches — and new pairs are then emitted only between unmatched
     * source rows and unmatched target rows. Each recovered source row joins
     * its single closest in-threshold target row.
     *
     * @param transformedSourceColumn the source column AFTER transformation (C)
     * @param targetKeyColumn         the original target key column (K)
     * @param sourceMatched           per source row: already joined by the equi-join
     * @param targetMatched           per target row: already joined by the equi-join
     * @return recovered row pairs (empty if no safe threshold exists)
     */
    public RecoveryOutcome recoverUnmatched(List<String> transformedSourceColumn,
                                           List<String> targetKeyColumn,
                                           boolean[] sourceMatched,
                                           boolean[] targetMatched) {
        // Eq. 10 (t, d, s) search: try each tokenization, keep its
        // cardinality-optimal threshold, and pick whichever recovers the most
        // rows. QGRAM is tried first and ties keep it, so WORD only wins when it
        // strictly recovers more — unchanged behavior on cases QGRAM already
        // handled. The cardinality constraints (constraint 1, plus
        // unmatched-only single-closest emission) guard precision regardless of
        // which tokenization is chosen.
        RecoveryOutcome best = new RecoveryOutcome(List.of(), 0.0);
        for (Tokenization tok : RECOVERY_SWEEP) {
            RecoveryOutcome outcome = recoverWith(tok, transformedSourceColumn,
                    targetKeyColumn, sourceMatched, targetMatched);
            if (outcome.getResults().size() > best.getResults().size()) {
                best = outcome;
            }
        }
        return best;
    }

    /** Recovery pass for a single tokenization scheme. */
    private RecoveryOutcome recoverWith(Tokenization tok,
                                        List<String> transformedSourceColumn,
                                        List<String> targetKeyColumn,
                                        boolean[] sourceMatched,
                                        boolean[] targetMatched) {
        DistanceModel model = new DistanceModel(transformedSourceColumn, targetKeyColumn, tok);
        if ((long) model.numSourceValues() * model.numTargetValues() > MAX_RECOVERY_PAIRS) {
            return new RecoveryOutcome(List.of(), 0.0);
        }

        double threshold = model.findOptimalThreshold();
        if (threshold <= 0.0) return new RecoveryOutcome(List.of(), 0.0);

        List<FuzzyJoinResult> recovered = new ArrayList<>();
        for (int i = 0; i < transformedSourceColumn.size(); i++) {
            if (sourceMatched[i]) continue;
            int ci = model.sourceValueIdx[i];
            if (ci < 0) continue;

            int bestJ = -1;
            double bestDistance = Double.MAX_VALUE;
            for (int j = 0; j < targetKeyColumn.size(); j++) {
                if (targetMatched[j]) continue;
                int ki = model.targetValueIdx[j];
                if (ki < 0) continue;

                double distance = model.distance(ci, ki);
                if (distance <= threshold && distance < bestDistance) {
                    bestDistance = distance;
                    bestJ = j;
                }
            }
            if (bestJ >= 0) {
                recovered.add(new FuzzyJoinResult(i, bestJ, bestDistance));
            }
        }
        return new RecoveryOutcome(recovered, threshold);
    }

    /**
     * Precomputed distance machinery shared by the threshold search and the
     * join/recovery passes. Distances only depend on string VALUES, so the
     * columns are deduplicated to distinct values: each value is q-gram
     * tokenized exactly once, the constraint checks run over distinct values
     * (weighted by target multiplicity, preserving the row-level semantics),
     * and pairwise distances are cached when the matrix fits.
     */
    private static final class DistanceModel {
        private final List<String> sourceValues = new ArrayList<>();   // distinct C values
        private final List<String> targetValues = new ArrayList<>();   // distinct K values
        private final List<Set<String>> sourceTokens = new ArrayList<>();
        private final List<Set<String>> targetTokens = new ArrayList<>();
        /** Per row: index into the distinct-value lists, or -1 for null. */
        final int[] sourceValueIdx;
        final int[] targetValueIdx;
        /** Rows per distinct target value (constraint 1 counts target ROWS). */
        private final int[] targetValueRowCount;
        /** Lazily filled distance cache (-1 = not computed); null if too large. */
        private final double[] matrix;

        DistanceModel(List<String> sourceColumn, List<String> targetColumn) {
            this(sourceColumn, targetColumn, Tokenization.QGRAM);
        }

        DistanceModel(List<String> sourceColumn, List<String> targetColumn, Tokenization tok) {
            sourceValueIdx = indexDistinct(sourceColumn, sourceValues, sourceTokens, tok);
            targetValueIdx = indexDistinct(targetColumn, targetValues, targetTokens, tok);

            targetValueRowCount = new int[targetValues.size()];
            for (int ki : targetValueIdx) {
                if (ki >= 0) targetValueRowCount[ki]++;
            }

            long entries = (long) sourceValues.size() * targetValues.size();
            if (entries > 0 && entries <= MAX_MATRIX_ENTRIES) {
                matrix = new double[(int) entries];
                Arrays.fill(matrix, -1.0);
            } else {
                matrix = null;
            }
        }

        int numSourceValues() { return sourceValues.size(); }
        int numTargetValues() { return targetValues.size(); }

        double distance(int ci, int ki) {
            if (matrix == null) return computeDistance(ci, ki);
            int idx = ci * targetValues.size() + ki;
            double d = matrix[idx];
            if (d < 0) {
                d = computeDistance(ci, ki);
                matrix[idx] = d;
            }
            return d;
        }

        private double computeDistance(int ci, int ki) {
            return jaccardDistance(sourceValues.get(ci), targetValues.get(ki),
                    sourceTokens.get(ci), targetTokens.get(ki));
        }

        /** Each distinct source value's closest target value (-1 if none). */
        int[] closestTargets() {
            int[] bestKi = new int[sourceValues.size()];
            for (int ci = 0; ci < sourceValues.size(); ci++) {
                checkCancelled();
                int best = -1;
                double bestD = Double.MAX_VALUE;
                for (int ki = 0; ki < targetValues.size(); ki++) {
                    double d = distance(ci, ki);
                    if (d < bestD) { bestD = d; best = ki; }
                }
                bestKi[ci] = best;
            }
            return bestKi;
        }

        /**
         * Loosest threshold whose top-1 matches keep the distinct-source
         * constraint: no target value may be the emitted closest match of two
         * distinct source values whose distances are both within threshold.
         */
        double topOneThreshold(int[] bestKi) {
            double low = 0.0, high = 1.0, optimal = 0.0;
            while ((high - low) > EPSILON) {
                double mid = low + (high - low) / 2.0;
                if (topOneSatisfies(mid, bestKi)) {
                    optimal = mid;
                    low = mid;
                } else {
                    high = mid;
                }
            }
            return optimal;
        }

        private boolean topOneSatisfies(double threshold, int[] bestKi) {
            int[] claims = new int[targetValues.size()];
            for (int ci = 0; ci < sourceValues.size(); ci++) {
                int ki = bestKi[ci];
                if (ki >= 0 && distance(ci, ki) <= threshold && ++claims[ki] > 1) return false;
            }
            return true;
        }

        /** First target ROW holding each distinct target value. */
        int[] firstRowOfTargetValue(int numRows) {
            int[] first = new int[targetValues.size()];
            Arrays.fill(first, -1);
            for (int j = 0; j < numRows; j++) {
                int ki = targetValueIdx[j];
                if (ki >= 0 && first[ki] < 0) first[ki] = j;
            }
            return first;
        }

        /** Binary search for the loosest threshold that keeps the constraint. */
        double findOptimalThreshold() {
            double low = 0.0;
            double high = 1.0;
            double optimalThreshold = 0.0;

            while ((high - low) > EPSILON) {
                double mid = low + (high - low) / 2.0;

                if (satisfiesConstraints(mid)) {
                    // store found threshold and update lower bound to continue search
                    optimalThreshold = mid;
                    low = mid;
                } else {
                    // if the threshold breaks a constraint it is too loose, causing rows to match that break the 1:1 and N:1 relationships
                    high = mid;
                }
            }
            return optimalThreshold;
        }

        /**
         * Checks if a given threshold respects the mandatory cardinality
         * constraint: every value in C matches at most 1 target ROW (1:1 or
         * N:1). Duplicate target key values count per row, so a duplicated key
         * still trips the constraint. The paper's OPTIONAL second constraint
         * (each target joined by at most one distinct source value) is NOT
         * checked all-pairs here — on columns full of similarly-formatted
         * values it collapses the threshold to ~0; the recovery pass enforces
         * it through single-closest emission instead, and the standalone FJ-C
         * path checks it on its emitted top-1 matches (topOneSatisfies).
         */
        private boolean satisfiesConstraints(double threshold) {
            for (int ci = 0; ci < sourceValues.size(); ci++) {
                checkCancelled();
                int matchCount = 0;
                for (int ki = 0; ki < targetValues.size(); ki++) {
                    if (distance(ci, ki) <= threshold) {
                        matchCount += targetValueRowCount[ki];
                        if (matchCount > 1) return false;
                    }
                }
            }
            return true;
        }

        /** Dedupe a column to distinct values, tokenizing each value once. */
        private static int[] indexDistinct(List<String> column,
                                           List<String> values,
                                           List<Set<String>> tokens,
                                           Tokenization tok) {
            int[] idx = new int[column.size()];
            Map<String, Integer> seen = new HashMap<>();
            for (int r = 0; r < column.size(); r++) {
                String v = column.get(r);
                if (v == null) {
                    idx[r] = -1;
                    continue;
                }
                Integer e = seen.get(v);
                if (e == null) {
                    e = values.size();
                    seen.put(v, e);
                    values.add(v);
                    tokens.add(tokenize(v, tok));
                }
                idx[r] = e;
            }
            return idx;
        }
    }

    /**
     * Calculates the Jaccard distance using pre-tokenized q-gram sets.
     * 0.0 is minimal distance, 1.0 is maximal distance
     */
    private static double jaccardDistance(String stringA, String stringB,
                                          Set<String> setA, Set<String> setB) {
        if (stringA.isBlank() && stringB.isBlank()) return 0.0;
        if (stringA.isBlank() || stringB.isBlank()) return 1.0;
        if (stringA.equals(stringB)) return 0.0;

        // Intersect iterating the smaller set
        Set<String> small = setA.size() <= setB.size() ? setA : setB;
        Set<String> large = (small == setA) ? setB : setA;
        int intersection = 0;
        for (String g : small) {
            if (large.contains(g)) intersection++;
        }

        int union = setA.size() + setB.size() - intersection;

        // Jaccard Distance = 1.0 - (Intersection / Union)
        return 1.0 - (double) intersection / union;
    }

    /** Tokenize a value under the given scheme. */
    private static Set<String> tokenize(String text, Tokenization tok) {
        switch (tok) {
            case WORD:   return tokenizeToWords(text);
            case QGRAM2: return tokenizeToQGrams(text, 2);
            default:     return tokenizeToQGrams(text, q);
        }
    }

    /**
     * Splits a string into whitespace-delimited word tokens. Punctuation is kept
     * attached (e.g. "K." stays one token) so it matches identically on both
     * sides; an empty/blank string yields an empty set, which {@link
     * #jaccardDistance} already maps to distance 0 (both blank) or 1 (one blank).
     */
    private static Set<String> tokenizeToWords(String text) {
        Set<String> words = new HashSet<>();
        for (String w : text.trim().split("\\s+")) {
            if (!w.isEmpty()) words.add(w);
        }
        return words;
    }

    /**
     * Breaks a string down into overlapping chunks of length {@code size} (q-grams).
     */
    private static Set<String> tokenizeToQGrams(String text, int size) {
        Set<String> qGrams = new HashSet<>();

        if (text.length() < size) {
            // If the word is shorter than the gram size, return it as a single token
            qGrams.add(text);
            return qGrams;
        }

        // Slide a window of length 'size' across the string
        for (int i = 0; i <= text.length() - size; i++) {
            qGrams.add(text.substring(i, i + size));
        }

        return qGrams;
    }
}
