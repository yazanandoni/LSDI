package com.autojoin.synthesis;

import com.autojoin.model.Column;
import com.autojoin.model.Row;
import com.autojoin.model.Table;
import com.autojoin.q_gram.ColumnPairMatches;
import com.autojoin.q_gram.MatchResult;

import java.util.*;

/**
 * Implements Algorithm 4 (LearnTransform) from the paper.
 *
 * For each (sourceColumn, targetKeyColumn) pair produced by the q-gram
 * discovery phase, this class:
 *   1. Takes the top-k joinable row pairs (by q-gram score).
 *   2. Generates r random subsets of size b from those pairs.
 *   3. Calls TryLearnTransform on each subset via TransformationSynthesizer.
 *   4. Applies every successful transformation to the full source table and
 *      counts how many target key values it covers (equi-join coverage).
 *   5. Returns the LearnedTransformation with the highest coverage.
 *
 * Paper parameters (Section 3.2 / Algorithm 4):
 *   k = 10   top matches used per group
 *   b = 3    examples per learning attempt
 *   r = 5    random subsets tried per group
 */
public class TransformationLearner {

    private static final int TOP_K = 10;
    private static final int SUBSET_SIZE = 3;
    private static final int SUBSETS_PER_GROUP = 5;

    private final TransformationSynthesizer synthesizer = new TransformationSynthesizer();

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Result of a successful learning attempt.
     */
    public static final class LearnedTransformation {
        public final TransformationProgram program;
        /** Name of the source column the transformation is applied to (its row index
         *  is the column's position in the source table). */
        public final String sourceColumnName;
        /** Name of the target key column that the transformation output should match. */
        public final String targetColumnName;
        /** Number of target key values covered by this transformation. */
        public final int coverage;

        LearnedTransformation(TransformationProgram program,
                               String sourceColumnName,
                               String targetColumnName,
                               int coverage) {
            this.program = program;
            this.sourceColumnName = sourceColumnName;
            this.targetColumnName = targetColumnName;
            this.coverage = coverage;
        }
    }

    /**
     * Try to learn a transformation from sourceTable to targetTable's key columns.
     *
     * @param columnPairMatches q-gram matches grouped by (sourceCol, targetCol)
     * @param sourceTable       the source table whose rows are the transformation input
     * @param targetTable       the target table — we match against its key columns
     * @return the best LearnedTransformation found, or null if none succeeded
     */
    public LearnedTransformation learn(List<ColumnPairMatches> columnPairMatches,
                                       Table sourceTable,
                                       Table targetTable) {
        LearnedTransformation best = null;

        for (ColumnPairMatches group : columnPairMatches) {
            String srcColName = group.getSourceColumnName();
            String tgtColName = group.getTargetColumnName();

            Optional<Column> tgtColOpt = targetTable.getColumn(tgtColName);
            if (tgtColOpt.isEmpty()) continue;
            Column tgtCol = tgtColOpt.get();

            List<MatchResult> matches = group.getMatches();
            if (matches.isEmpty()) continue;

            // Top-k matches
            List<MatchResult> topK = matches.subList(0, Math.min(TOP_K, matches.size()));

            // Build the pool of (sourceRowIdx, targetRowIdx) pairs
            List<int[]> pairs = buildPairs(topK);
            if (pairs.isEmpty()) continue;

            // Generate random subsets of size b
            List<List<int[]>> subsets = randomSubsets(pairs, SUBSET_SIZE, SUBSETS_PER_GROUP);

            for (List<int[]> subset : subsets) {
                List<ExamplePair> examples = toExamplePairs(subset, sourceTable, tgtCol);
                if (examples.isEmpty()) continue;

                TransformationProgram program = synthesizer.tryLearnTransform(examples);
                if (program == null) continue;

                int coverage = computeCoverage(program, sourceTable, tgtCol);
                if (best == null || coverage > best.coverage) {
                    best = new LearnedTransformation(program, srcColName, tgtColName, coverage);
                }
            }
        }

        return best;
    }

    // -------------------------------------------------------------------------
    // Coverage computation
    // -------------------------------------------------------------------------

    /**
     * Apply the program to every source row; count how many distinct target key
     * values are matched by at least one source row.
     */
    static int computeCoverage(TransformationProgram program,
                                Table sourceTable,
                                Column targetKeyColumn) {
        Set<String> targetKeySet = new HashSet<>(targetKeyColumn.getValues());
        Set<String> covered = new HashSet<>();

        for (int i = 0; i < sourceTable.numRows(); i++) {
            String[] row = rowToArray(sourceTable.getRow(i));
            String derived = program.apply(row);
            if (derived != null && targetKeySet.contains(derived)) {
                covered.add(derived);
            }
        }
        return covered.size();
    }

    /**
     * Produce the full equi-join result: for each source row, apply the program
     * and match against the target key column. Returns matched (sourceRow, targetRow) pairs.
     */
    public static List<Row[]> applyJoin(TransformationProgram program,
                                         Table sourceTable,
                                         Table targetTable,
                                         String targetKeyColumnName) {
        Optional<Column> tgtColOpt = targetTable.getColumn(targetKeyColumnName);
        if (tgtColOpt.isEmpty()) return List.of();
        Column tgtCol = tgtColOpt.get();

        // Build index: target key value → row index (1:1 or N:1)
        Map<String, Integer> targetIndex = new HashMap<>();
        for (int i = 0; i < tgtCol.getValues().size(); i++) {
            targetIndex.putIfAbsent(tgtCol.getValue(i), i);
        }

        List<Row[]> results = new ArrayList<>();
        for (int i = 0; i < sourceTable.numRows(); i++) {
            String[] rowArr = rowToArray(sourceTable.getRow(i));
            String derived = program.apply(rowArr);
            if (derived == null) continue;
            Integer tgtIdx = targetIndex.get(derived);
            if (tgtIdx != null) {
                results.add(new Row[]{sourceTable.getRow(i), targetTable.getRow(tgtIdx)});
            }
        }
        return results;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static List<int[]> buildPairs(List<MatchResult> matches) {
        List<int[]> pairs = new ArrayList<>();
        for (MatchResult m : matches) {
            List<Integer> srcRows = m.getBestSourceRows();
            List<Integer> tgtRows = m.getBestTargetRows();
            if (srcRows.isEmpty() || tgtRows.isEmpty()) continue;
            // Use the first source row and first target row from each match result
            pairs.add(new int[]{srcRows.get(0), tgtRows.get(0)});
        }
        return pairs;
    }

    private static List<List<int[]>> randomSubsets(List<int[]> pairs, int size, int count) {
        if (pairs.size() <= size) {
            return List.of(new ArrayList<>(pairs));
        }
        Random rng = new Random(42); // deterministic for reproducibility
        List<List<int[]>> subsets = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        int attempts = 0;
        while (subsets.size() < count && attempts < count * 10) {
            attempts++;
            List<int[]> copy = new ArrayList<>(pairs);
            Collections.shuffle(copy, rng);
            List<int[]> subset = copy.subList(0, size);
            String key = subsetKey(subset);
            if (seen.add(key)) {
                subsets.add(new ArrayList<>(subset));
            }
        }
        return subsets;
    }

    private static String subsetKey(List<int[]> subset) {
        StringBuilder sb = new StringBuilder();
        for (int[] p : subset) sb.append(p[0]).append(':').append(p[1]).append(',');
        return sb.toString();
    }

    private static List<ExamplePair> toExamplePairs(List<int[]> pairs,
                                                      Table sourceTable,
                                                      Column targetKeyColumn) {
        List<ExamplePair> examples = new ArrayList<>();
        for (int[] p : pairs) {
            int srcIdx = p[0], tgtIdx = p[1];
            if (srcIdx >= sourceTable.numRows() || tgtIdx >= targetKeyColumn.size()) continue;
            String[] srcRow = rowToArray(sourceTable.getRow(srcIdx));
            String tgtValue = targetKeyColumn.getValue(tgtIdx);
            if (tgtValue == null || tgtValue.isBlank()) continue;
            examples.add(new ExamplePair(srcRow, tgtValue));
        }
        return examples;
    }

    static String[] rowToArray(Row row) {
        return row.getColumnNames().stream()
                .map(row::get)
                .toArray(String[]::new);
    }
}
