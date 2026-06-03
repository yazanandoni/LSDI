package com.autojoin.synthesis;

import com.autojoin.model.Column;
import com.autojoin.model.Row;
import com.autojoin.model.Table;
import com.autojoin.operator.ConstantOp;
import com.autojoin.operator.LogicalOperator;
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
 *      scores it by how many derived keys form a clean 1:1 join — i.e. a key
 *      produced by exactly one source row that matches exactly one target row.
 *      Collisions on either side (many source rows → one key, or one key → many
 *      target rows) are excluded, so lossy projections and degenerate constants
 *      score poorly.
 *   5. Rejects constant-only programs and returns the LearnedTransformation with
 *      the highest injective score (null if none scores at least 1).
 *
 * Paper parameters (Section 3.2 / Algorithm 4):
 *   k = 10   top matches used per group
 *   b = 3    examples per learning attempt
 *   r = 5    random subsets tried per group
 */
public class TransformationLearner {

    private static final int TOP_K = 20;
    private static final int SUBSET_SIZE = 3;
    // Number of random-subset trials per group (the paper's T). Proposition 3
    // (§3.2) shows the learning failure probability drops exponentially in T.
    // More trials let the high-coverage transform surface even when the top-k
    // mixes easy exact-match pairs with the transform-requiring pairs (e.g.
    // Beatles), and raise the odds of sampling a clean all-corresponding subset
    // on noisy column pairs. Doomed pairs are still cut early by
    // MAX_GROUP_ABORTS, bounding the extra cost.
    private static final int SUBSETS_PER_GROUP = 12;

    /**
     * Minimum length of a derived key for it to count toward the injective score.
     * A transformation that joins on a 1–2 character fragment is almost never a
     * real key — it is the degenerate program the synthesizer falls back to when
     * no genuine transform exists (e.g. a 2-char Substr that forms a handful of
     * accidental 1:1 matches). Such fragments collide heavily and produce
     * spurious joins, so they are not credited and the program is rejected by the
     * score &lt; 1 guard. Real keys in practice (names, years, emails, cities) are
     * comfortably longer than this.
     */
    private static final int MIN_DERIVED_KEY_LEN = 3;

    /**
     * Stop trying subsets of a column pair after this many search-guard aborts.
     * Raised from 2 so the extra T trials (SUBSETS_PER_GROUP) are actually used:
     * a genuinely joinable pair whose first subsets happen to draw hard, slow-to-
     * synthesize examples (e.g. name columns padded with long date strings) gets
     * more chances to hit a workable subset before the pair is written off. Truly
     * non-joinable pairs still abort every attempt and are cut once this many are
     * seen.
     */
    private static final int MAX_GROUP_ABORTS = 4;

    /** Enable verbose timing on stderr via -Dautojoin.debug=true. */
    static final boolean DEBUG = Boolean.getBoolean("autojoin.debug");

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
        /** Injective-join score: number of derived keys that map exactly one
         *  source row to exactly one target row. */
        public final int score;

        LearnedTransformation(TransformationProgram program,
                               String sourceColumnName,
                               String targetColumnName,
                               int score) {
            this.program = program;
            this.sourceColumnName = sourceColumnName;
            this.targetColumnName = targetColumnName;
            this.score = score;
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

            long groupStart = System.nanoTime();
            int attempts = 0, slowAttempts = 0, aborts = 0;

            for (List<int[]> subset : subsets) {
                List<ExamplePair> examples = toExamplePairs(subset, sourceTable, tgtCol);
                if (examples.isEmpty()) continue;

                attempts++;
                long t0 = System.nanoTime();
                TransformationProgram program = synthesizer.tryLearnTransform(examples);
                long ms = (System.nanoTime() - t0) / 1_000_000;
                if (ms > 200) {
                    slowAttempts++;
                    if (DEBUG) System.err.printf(
                            "    [learn] SLOW attempt %dms  %s->%s  nodes=%d  examples=%s%n",
                            ms, srcColName, tgtColName, synthesizer.lastNodesVisited(),
                            describeExamples(examples));
                }

                // Doomed-pair early stop: if multiple subsets exhaust the search
                // guard without yielding a program, the column pair almost
                // certainly does not join — stop wasting attempts on it.
                if (synthesizer.lastAttemptAborted() && ++aborts >= MAX_GROUP_ABORTS) {
                    if (DEBUG) System.err.printf(
                            "    [learn] abandoning %s->%s after %d aborts%n",
                            srcColName, tgtColName, aborts);
                    break;
                }

                if (program == null) continue;

                // Reject degenerate constant-only programs: they ignore the
                // source row and collapse every row onto one fixed key.
                if (isConstantOnly(program)) continue;

                int score = computeScore(program, sourceTable, tgtCol);
                if (score < 1) continue; // no clean 1:1 match — not a real join
                if (best == null || score > best.score) {
                    best = new LearnedTransformation(program, srcColName, tgtColName, score);
                }
            }

            if (DEBUG) {
                long gms = (System.nanoTime() - groupStart) / 1_000_000;
                System.err.printf(
                        "  [learn] group %s->%s  matches=%d topK=%d pairs=%d  attempts=%d (slow=%d)  %dms%n",
                        srcColName, tgtColName, matches.size(), topK.size(), pairs.size(),
                        attempts, slowAttempts, gms);
            }
        }

        return best;
    }

    /** Compact one-line view of example pairs, for debug logging. */
    private static String describeExamples(List<ExamplePair> examples) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < examples.size(); i++) {
            if (i > 0) sb.append("; ");
            ExamplePair ex = examples.get(i);
            sb.append(String.join(",", ex.sourceRow)).append(" => ").append(ex.targetValue);
        }
        return sb.append(']').toString();
    }

    // -------------------------------------------------------------------------
    // Injective-join scoring
    // -------------------------------------------------------------------------

    /**
     * Score a transformation by how many derived keys form a clean 1:1 join.
     *
     * A derived key counts only when it is produced by exactly one source row
     * AND matches exactly one target row. This rewards transformations that
     * uniquely identify a join partner and penalizes:
     *   - source-side collisions (many source rows → the same key), which is how
     *     numeric-column joins and constants degenerate, and
     *   - target-side collisions (one key → many target rows), which is how a
     *     lossy projection of a composite key (e.g. dropping "Type") degenerates.
     */
    static int computeScore(TransformationProgram program,
                            Table sourceTable,
                            Column targetKeyColumn) {
        // How many target rows hold each target key value.
        Map<String, Integer> targetValueCounts = new HashMap<>();
        for (String v : targetKeyColumn.getValues()) {
            targetValueCounts.merge(v, 1, Integer::sum);
        }

        // How many source rows produce each derived value.
        Map<String, Integer> derivedCounts = new HashMap<>();
        for (int i = 0; i < sourceTable.numRows(); i++) {
            String[] row = rowToArray(sourceTable.getRow(i));
            String derived = program.apply(row);
            if (derived != null) {
                derivedCounts.merge(derived, 1, Integer::sum);
            }
        }

        int score = 0;
        for (Map.Entry<String, Integer> e : derivedCounts.entrySet()) {
            if (e.getValue() != 1) continue;                       // many source rows → ambiguous
            if (e.getKey().length() < MIN_DERIVED_KEY_LEN) continue; // degenerate short fragment
            Integer tgtCount = targetValueCounts.get(e.getKey());
            if (tgtCount != null && tgtCount == 1) score++;        // matches exactly one target row
        }
        return score;
    }

    /** True if every operator in the program is a row-independent Constant. */
    private static boolean isConstantOnly(TransformationProgram program) {
        List<LogicalOperator> ops = program.getOperators();
        if (ops.isEmpty()) return true;
        for (LogicalOperator op : ops) {
            if (!(op instanceof ConstantOp)) return false;
        }
        return true;
    }

    /**
     * Produce the equi-join result: for each source row, apply the program and
     * match against the target key column. Returns matched (sourceRow, targetRow)
     * pairs.
     *
     * Only clean 1-to-1 matches are emitted — a derived key produced by exactly
     * one source row AND matching exactly one target row. This is the same
     * injective criterion {@link #computeScore} uses to select the transform, so
     * the output is consistent with the score the transform was chosen on.
     * Emitting ambiguous matches would silently lower precision: a lossy
     * transform that projects away part of a composite key (e.g. extracting only
     * the song title from a "Song - Artist" key) scores on its unique matches but
     * would otherwise also emit wrong joins for every collision (e.g. "White
     * Christmas" joining to the wrong cover). When a derived key is ambiguous we
     * genuinely cannot tell which row is correct, so we omit it rather than guess.
     */
    public static List<Row[]> applyJoin(TransformationProgram program,
                                         Table sourceTable,
                                         Table targetTable,
                                         String targetKeyColumnName) {
        Optional<Column> tgtColOpt = targetTable.getColumn(targetKeyColumnName);
        if (tgtColOpt.isEmpty()) return List.of();
        Column tgtCol = tgtColOpt.get();

        // How many target rows hold each target key value (for target-side uniqueness).
        Map<String, Integer> targetValueCounts = new HashMap<>();
        for (String v : tgtCol.getValues()) {
            targetValueCounts.merge(v, 1, Integer::sum);
        }
        // First target row index for each key value.
        Map<String, Integer> targetIndex = new HashMap<>();
        for (int i = 0; i < tgtCol.getValues().size(); i++) {
            targetIndex.putIfAbsent(tgtCol.getValue(i), i);
        }

        // How many source rows produce each derived key (for source-side uniqueness).
        Map<String, Integer> derivedCounts = new HashMap<>();
        for (int i = 0; i < sourceTable.numRows(); i++) {
            String derived = program.apply(rowToArray(sourceTable.getRow(i)));
            if (derived != null) derivedCounts.merge(derived, 1, Integer::sum);
        }

        List<Row[]> results = new ArrayList<>();
        for (int i = 0; i < sourceTable.numRows(); i++) {
            String[] rowArr = rowToArray(sourceTable.getRow(i));
            String derived = program.apply(rowArr);
            if (derived == null) continue;
            Integer dCount = derivedCounts.get(derived);
            if (dCount == null || dCount != 1) continue;       // source-side ambiguous
            Integer tCount = targetValueCounts.get(derived);
            if (tCount == null || tCount != 1) continue;       // target-side ambiguous / no match
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
        // Positional values: preserves every column even when two share a name.
        return row.getValues().toArray(new String[0]);
    }
}
