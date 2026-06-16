package com.autojoin.fuzzy_join;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
        DistanceModel model = new DistanceModel(transformedSourceColumn, targetKeyColumn);
        double optimalThreshold = model.findOptimalThreshold(true);

        List<FuzzyJoinResult> joinedRows = new ArrayList<>();
        for (int i = 0; i < transformedSourceColumn.size(); i++) {
            int ci = model.sourceValueIdx[i];
            if (ci < 0) continue;

            for (int j = 0; j < targetKeyColumn.size(); j++) {
                int ki = model.targetValueIdx[j];
                if (ki < 0) continue;

                double distance = model.distance(ci, ki);
                if (distance <= optimalThreshold) {
                    joinedRows.add(new FuzzyJoinResult(i, j, distance));
                }
            }
        }

        return joinedRows;
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
        DistanceModel model = new DistanceModel(transformedSourceColumn, targetKeyColumn);
        if ((long) model.numSourceValues() * model.numTargetValues() > MAX_RECOVERY_PAIRS) {
            return new RecoveryOutcome(List.of(), 0.0);
        }

        // Constraint 2 relaxed for recovery — see satisfiesConstraints.
        double threshold = model.findOptimalThreshold(false);
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
     * Finds the optimal (maximum) distance threshold that satisfies the join constraints.
     *
     * @param transformedSourceColumn The source column AFTER learned transformation is applied (C)
     * @param targetKeyColumn         The original target key column (K)
     * @return The maximum safe distance threshold [0.0, 1.0]
     */
    public double findOptimalThreshold(List<String> transformedSourceColumn, List<String> targetKeyColumn) {
        return new DistanceModel(transformedSourceColumn, targetKeyColumn).findOptimalThreshold(true);
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
            sourceValueIdx = indexDistinct(sourceColumn, sourceValues, sourceTokens);
            targetValueIdx = indexDistinct(targetColumn, targetValues, targetTokens);

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

        /** Binary search for the loosest threshold that keeps the constraints. */
        double findOptimalThreshold(boolean enforceDistinctSourceConstraint) {
            double low = 0.0;
            double high = 1.0;
            double optimalThreshold = 0.0;

            while ((high - low) > EPSILON) {
                double mid = low + (high - low) / 2.0;

                if (satisfiesConstraints(mid, enforceDistinctSourceConstraint)) {
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
         * Checks if a given threshold respects the 1:1 or N:1 key constraints.
         */
        private boolean satisfiesConstraints(double threshold, boolean enforceDistinctSourceConstraint) {
            // Constraint 1: Every value in C matches <= 1 target ROW -> ensuring
            // 1:1 or N:1 relationship. Duplicate target key values count per row,
            // so a duplicated key still trips the constraint.
            for (int ci = 0; ci < sourceValues.size(); ci++) {
                int matchCount = 0;
                for (int ki = 0; ki < targetValues.size(); ki++) {
                    if (distance(ci, ki) <= threshold) {
                        matchCount += targetValueRowCount[ki];
                        if (matchCount > 1) return false;
                    }
                }
            }

            // Constraint 2 (paper: OPTIONAL — "every value in K matches <= 1
            // DISTINCT value in C"). Enforced for the standalone executeJoin
            // API, but NOT for the recovery pass: on columns full of
            // similarly-formatted values (e.g. 75 "The Earl of X"
            // prime-minister names) two distinct source values almost always
            // sit near some shared target, which collapses the optimal
            // threshold to ~0 and disables recovery entirely — the paper's
            // ~0.11 recall gain from fuzzy join never materializes. Recovery
            // precision is still protected by constraint 1 plus the recovery
            // rules (unmatched rows only, single closest match per row).
            if (enforceDistinctSourceConstraint) {
                for (int ki = 0; ki < targetValues.size(); ki++) {
                    int distinctSourceMatches = 0;
                    for (int ci = 0; ci < sourceValues.size(); ci++) {
                        if (distance(ci, ki) <= threshold) {
                            if (++distinctSourceMatches > 1) return false;
                        }
                    }
                }
            }
            return true;
        }

        /** Dedupe a column to distinct values, tokenizing each value once. */
        private static int[] indexDistinct(List<String> column,
                                           List<String> values,
                                           List<Set<String>> tokens) {
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
                    tokens.add(tokenizeToQGrams(v));
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

    /**
     * Breaks a string down into overlapping chunks of length q (q-grams).
     */
    private static Set<String> tokenizeToQGrams(String text) {
        Set<String> qGrams = new HashSet<>();

        if (text.length() < q) {
            // If the word is shorter than 'q', just return the word itself as a single token
            qGrams.add(text);
            return qGrams;
        }

        // Slide a window of length 'q' across the string
        for (int i = 0; i <= text.length() - q; i++) {
            qGrams.add(text.substring(i, i + q));
        }

        return qGrams;
    }
}
