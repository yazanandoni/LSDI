package com.autojoin;

import com.autojoin.model.Row;
import com.autojoin.model.Table;
import com.autojoin.q_gram.AutoJoinDiscovery;
import com.autojoin.q_gram.ColumnPairMatches;
import com.autojoin.synthesis.TransformationLearner;
import com.autojoin.synthesis.TransformationLearner.LearnedTransformation;

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
        JoinResult forward = tryDirection(ts, tt);
        JoinResult backward = tryDirection(tt, ts);

        if (forward.isEmpty() && backward.isEmpty()) return JoinResult.empty();
        if (forward.isEmpty()) return backward;
        if (backward.isEmpty()) return forward;
        return forward.size() >= backward.size() ? forward : backward;
    }

    // -------------------------------------------------------------------------
    // Single-direction attempt: transform sourceTable → match targetTable keys
    // -------------------------------------------------------------------------

    private JoinResult tryDirection(Table sourceTable, Table targetTable) {
        // Phase 1: find joinable row pairs via q-gram matching
        List<ColumnPairMatches> matches =
                discovery.findJoinableRowPairs(sourceTable, targetTable);

        if (matches.isEmpty()) return JoinResult.empty();

        // Phase 2: learn transformation from row pairs
        LearnedTransformation learned = learner.learn(matches, sourceTable, targetTable);
        if (learned == null) return JoinResult.empty();

        // Phase 3: apply transformation as equi-join
        List<Row[]> joinedPairs = TransformationLearner.applyJoin(
                learned.program, sourceTable, targetTable, learned.targetColumnName);

        if (joinedPairs.isEmpty()) return JoinResult.empty();

        return JoinResult.of(joinedPairs, learned.program.describe());
    }
}
