package com.autojoin;

import com.autojoin.model.Row;
import com.autojoin.model.Table;
import com.autojoin.q_gram.AutoJoinDiscovery;
import com.autojoin.q_gram.ColumnPairMatches;
import com.autojoin.synthesis.TransformationLearner;
import com.autojoin.synthesis.TransformationLearner.LearnedTransformation;

import java.util.ArrayList;
import java.util.List;

/**
 * Main entry point for the Auto-Join algorithm.
 *
 * Pipeline (Zhu et al., VLDB 2017):
 *   1. Find joinable row pairs via q-gram matching on suffix array indexes.
 *   2. Learn a minimum-complexity transformation program from those row pairs.
 *   3. Apply the transformation as an equi-join over both tables.
 *
 * Both directions (ts→tt and tt→ts) are tried; the result with higher row
 * coverage is returned.
 */
public class AutoJoin {

    private final AutoJoinDiscovery discovery = new AutoJoinDiscovery();
    private final TransformationLearner learner = new TransformationLearner();

    /**
     * Attempt to join two tables by discovering transformations in both
     * directions and returning the result with higher row coverage.
     *
     * @param ts first table
     * @param tt second table
     * @return the best join result found, or an empty result if none
     */
    public JoinResult join(Table ts, Table tt) {
        DirectionResult forward = tryDirection(ts, tt);
        DirectionResult backward = tryDirection(tt, ts);

        if (forward.join.isEmpty() && backward.join.isEmpty()) return JoinResult.empty();
        if (forward.join.isEmpty()) return orientSourceTarget(backward.join);
        if (backward.join.isEmpty()) return forward.join;

        // Prefer the direction whose learned transformation forms the cleaner
        // 1:1 join (higher injective score). A lossy projection can produce more
        // raw pairs by collapsing many rows onto one key, so raw pair count is
        // not a reliable tie-break — only fall back to it when scores are equal.
        if (forward.score != backward.score) {
            return forward.score > backward.score ? forward.join : orientSourceTarget(backward.join);
        }
        return forward.join.size() >= backward.join.size()
                ? forward.join : orientSourceTarget(backward.join);
    }

    /**
     * Re-orient a backward-direction result so every pair is [userSource, userTarget].
     *
     * The backward attempt runs tryDirection(tt, ts), so its pairs are emitted as
     * [tt-row, ts-row] — the reverse of {@link JoinResult}'s documented
     * [sourceRow, targetRow] contract. Swapping here keeps that contract stable no
     * matter which internal direction wins, so downstream consumers (and the
     * benchmark evaluator) can always trust pair[0]=source, pair[1]=target. Without
     * this, a backward win silently swaps the columns — undetectable whenever the
     * source's key column name also exists in the target table.
     */
    private static JoinResult orientSourceTarget(JoinResult backward) {
        List<Row[]> swapped = new ArrayList<>(backward.size());
        for (Row[] pair : backward.getJoinedPairs()) {
            swapped.add(new Row[]{pair[1], pair[0]});
        }
        return JoinResult.of(swapped, backward.getTransformationDescription());
    }

    // -------------------------------------------------------------------------
    // Single-direction attempt: transform sourceTable → match targetTable keys
    // -------------------------------------------------------------------------

    /** A join result paired with the injective score of the transformation that produced it. */
    private static final class DirectionResult {
        final JoinResult join;
        final int score;

        DirectionResult(JoinResult join, int score) {
            this.join = join;
            this.score = score;
        }

        static DirectionResult empty() {
            return new DirectionResult(JoinResult.empty(), 0);
        }
    }

    private DirectionResult tryDirection(Table sourceTable, Table targetTable) {
        boolean debug = Boolean.getBoolean("autojoin.debug");
        if (debug) System.err.printf("[direction] %s (%d rows) -> %s (%d rows)%n",
                sourceTable.getName(), sourceTable.numRows(),
                targetTable.getName(), targetTable.numRows());

        // Phase 1: find joinable row pairs via q-gram matching
        long t0 = System.nanoTime();
        List<ColumnPairMatches> matches =
                discovery.findJoinableRowPairs(sourceTable, targetTable);
        if (debug) System.err.printf("  [discovery] %d groups in %dms%n",
                matches.size(), (System.nanoTime() - t0) / 1_000_000);

        if (matches.isEmpty()) return DirectionResult.empty();

        // Phase 2: learn transformation from row pairs
        long t1 = System.nanoTime();
        LearnedTransformation learned = learner.learn(matches, sourceTable, targetTable);
        if (debug) System.err.printf("  [learn] total %dms%n", (System.nanoTime() - t1) / 1_000_000);
        if (learned == null) return DirectionResult.empty();

        // Phase 3: apply transformation as equi-join
        List<Row[]> joinedPairs = TransformationLearner.applyJoin(
                learned.program, sourceTable, targetTable, learned.targetColumnName);

        if (joinedPairs.isEmpty()) return DirectionResult.empty();

        return new DirectionResult(
                JoinResult.of(joinedPairs, learned.program.describe()), learned.score);
    }
}
