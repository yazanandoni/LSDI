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
    private static final double EPSILON = 0.001;
    private static final int q = 3;

    private static void checkCancelled() {
        if (Thread.currentThread().isInterrupted())
            throw new CancellationException("fuzzy join cancelled (timeout)");
    }

    private enum Tokenization { QGRAM, QGRAM2, WORD }

    private static final List<Tokenization> BASELINE_SWEEP =
            List.of(Tokenization.QGRAM, Tokenization.QGRAM2, Tokenization.WORD);
    private static final List<Tokenization> RECOVERY_SWEEP =
            List.of(Tokenization.QGRAM, Tokenization.WORD);

    private static final long MAX_MATRIX_ENTRIES = 4_000_000L;

    private static final long MAX_RECOVERY_PAIRS = 25_000_000L;

    /**
     * Executes the constrained fuzzy join and returns the joined row pairs.
     *
     * @param transformedSourceColumn The source column AFTER transformation (C)
     * @param targetKeyColumn         The original target key column (K)
     * @return A list of successfully joined row pairs
     */
    public List<FuzzyJoinResult> executeJoin(List<String> transformedSourceColumn, List<String> targetKeyColumn) {
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
        RecoveryOutcome best = new RecoveryOutcome(List.of(), 0.0);
        if (countDistinct(transformedSourceColumn) * countDistinct(targetKeyColumn) > MAX_RECOVERY_PAIRS) {
            return best;
        }
        for (Tokenization tok : RECOVERY_SWEEP) {
            RecoveryOutcome outcome = recoverWith(tok, transformedSourceColumn,
                    targetKeyColumn, sourceMatched, targetMatched);
            if (outcome.getResults().size() > best.getResults().size()) {
                best = outcome;
            }
        }
        return best;
    }

    private RecoveryOutcome recoverWith(Tokenization tok,
                                        List<String> transformedSourceColumn,
                                        List<String> targetKeyColumn,
                                        boolean[] sourceMatched,
                                        boolean[] targetMatched) {
        DistanceModel model = new DistanceModel(transformedSourceColumn, targetKeyColumn, tok);
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

        int[] firstRowOfTargetValue(int numRows) {
            int[] first = new int[targetValues.size()];
            Arrays.fill(first, -1);
            for (int j = 0; j < numRows; j++) {
                int ki = targetValueIdx[j];
                if (ki >= 0 && first[ki] < 0) first[ki] = j;
            }
            return first;
        }

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
     * Number of distinct non-null values in a column — the same count the
     * DistanceModel would produce. Used to apply the MAX_RECOVERY_PAIRS guard
     * BEFORE a model is built: constructing one tokenizes every distinct value
     * of both full columns, which on million-row tables allocates tens of GB
     * only for the guard to then discard the pass entirely.
     */
    private static long countDistinct(List<String> column) {
        Set<String> seen = new HashSet<>();
        for (String v : column) {
            if (v != null) seen.add(v);
        }
        return seen.size();
    }

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

    private static Set<String> tokenize(String text, Tokenization tok) {
        switch (tok) {
            case WORD:   return tokenizeToWords(text);
            case QGRAM2: return tokenizeToQGrams(text, 2);
            default:     return tokenizeToQGrams(text, q);
        }
    }

    private static Set<String> tokenizeToWords(String text) {
        Set<String> words = new HashSet<>();
        for (String w : text.trim().split("\\s+")) {
            if (!w.isEmpty()) words.add(w);
        }
        return words;
    }

    private static Set<String> tokenizeToQGrams(String text, int size) {
        Set<String> qGrams = new HashSet<>();

        if (text.length() < size) {
            qGrams.add(text);
            return qGrams;
        }

        for (int i = 0; i <= text.length() - size; i++) {
            qGrams.add(text.substring(i, i + size));
        }

        return qGrams;
    }
}
