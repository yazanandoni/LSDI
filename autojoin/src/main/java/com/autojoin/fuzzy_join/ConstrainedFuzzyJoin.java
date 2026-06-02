package com.autojoin.fuzzy_join;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class ConstrainedFuzzyJoin {
    private static final double EPSILON = 0.001; // Epsilon for comparison in binary search
    private static final int q = 3; // size of q_gram used for distance calculation

    /**
     * Executes the constrained fuzzy join and returns the joined row pairs.
     *
     * @param transformedSourceColumn The source column AFTER transformation (C)
     * @param targetKeyColumn         The original target key column (K)
     * @return A list of successfully joined row pairs
     */
    public List<FuzzyJoinResult> executeJoin(List<String> transformedSourceColumn, List<String> targetKeyColumn) {
        List<FuzzyJoinResult> joinedRows = new ArrayList<>();
        double optimalThreshold = findOptimalThreshold(transformedSourceColumn, targetKeyColumn);

        for (int i = 0; i < transformedSourceColumn.size(); i++) {
            String sourceValue = transformedSourceColumn.get(i);
            if (sourceValue == null) continue;

            for (int j = 0; j < targetKeyColumn.size(); j++) {
                String targetValue = targetKeyColumn.get(j);
                if (targetValue == null) continue;

                double distance = calculateDistance(sourceValue, targetValue);
                if (distance <= optimalThreshold) {
                    joinedRows.add(new FuzzyJoinResult(i, j, distance));
                }
            }
        }

        return joinedRows;
    }

    /**
     * Finds the optimal (maximum) distance threshold that satisfies the join constraints.
     *
     * @param transformedSourceColumn The source column AFTER learned transformation is applied (C)
     * @param targetKeyColumn         The original target key column (K)
     * @return The maximum safe distance threshold [0.0, 1.0]
     */
    public double findOptimalThreshold(List<String> transformedSourceColumn, List<String> targetKeyColumn) {
        double low = 0.0;
        double high = 1.0;
        double optimalThreshold = 0.0;

        // Binary search for the best threshold
        while ((high - low) > EPSILON) {
            double mid = low + (high - low) / 2.0;

            if (satisfiesConstraints(transformedSourceColumn, targetKeyColumn, mid)) {
                // store found threshold and update lower bound to continue search
                optimalThreshold = mid;
                low = mid;
            } else {
                // if the threshold breaks a constraint it is to loose causing rows to match that break the 1:1 and N:1 relationships
                high = mid;
            }
        }
        return optimalThreshold;
    }

    /**
     * Checks if a given threshold respects the 1:1 or N:1 key constraints.
     */
    private boolean satisfiesConstraints(List<String> sourceC, List<String> targetK, double threshold) {

        // Constraint 1: Every value in C matches <= 1 value in K -> ensuring 1:1 or N:1 relationship
        for (String vc : sourceC) {
            int matchCount = 0;
            if (vc == null) continue;

            for (String vk : targetK) {
                if (vk == null) continue;
                if (calculateDistance(vc, vk) <= threshold) {
                    matchCount++;
                }
                if (matchCount > 1) {
                    return false;
                }
            }
        }

        // Constraint 2: Every value in K matches <= 1 DISTINCT value in C
        // basically the same check for 1:1 and N:1 as constraint 1 but kind of backwards
        // This is marked as optional in the paper and assumes that we don't have two different representations for the same entity in the source column
        for (String vk : targetK) {
            if (vk == null) continue;
            Set<String> uniqueSourceMatches = new HashSet<>();

            for (String vc : sourceC) {
                if (vc == null) continue;
                if (calculateDistance(vc, vk) <= threshold) {
                    uniqueSourceMatches.add(vc);
                }
                if (uniqueSourceMatches.size() > 1) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Calculates the Jaccard distance using q-gram tokenization.
     * 0.0 is minimal distance, 1.0 is maximal distance
     */
    private double calculateDistance(String stringA, String stringB) {
        if (stringA.isBlank() && stringB.isBlank()) return 0.0;
        if (stringA.isBlank() || stringB.isBlank()) return 1.0;
        if (stringA.equals(stringB)) return 0.0;

        Set<String> setA = tokenizeToQGrams(stringA);
        Set<String> setB = tokenizeToQGrams(stringB);

        // Calculate Intersection
        Set<String> intersection = new HashSet<>(setA);
        intersection.retainAll(setB);

        // Calculate Union
        Set<String> union = new HashSet<>(setA);
        union.addAll(setB);

        // Jaccard Similarity = Intersection / Union
        double jaccardSimilarity = (double) intersection.size() / union.size();

        // Jaccard Distance = 1.0 - Similarity
        return 1.0 - jaccardSimilarity;
    }

    /**
     * Breaks a string down into overlapping chunks of length q (q-grams).
     */
    private Set<String> tokenizeToQGrams(String text) {
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