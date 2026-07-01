package com.autojoin.baselines;

import com.autojoin.fuzzy_join.ConstrainedFuzzyJoin;
import com.autojoin.fuzzy_join.FuzzyJoinResult;
import com.autojoin.model.Row;

import java.util.ArrayList;
import java.util.List;

/**
 * FJ-C — Fuzzy Join, Column (paper §6.2).
 *
 * "We perform fuzzy join on columns that participate in joins in the ground
 * truth as if these are known, but without using detailed row-level ground
 * truth ... We use techniques discussed in Section 5 to determine the best
 * parameter configuration."
 *
 * So FJ-C is exactly the project's constrained fuzzy join (§5:
 * {@link ConstrainedFuzzyJoin#executeJoin}) run directly on the two known join
 * columns — no learned transformation. The threshold is auto-tuned by the §5
 * (t, d, s) search under the join-cardinality constraints; no ground-truth row
 * pairs are consulted, which is what separates FJ-C from the FJ-O oracle.
 *
 * Multi-column keys are reduced to one join value per row by concatenating the
 * case's key columns (same reduction used by the other baselines), so every
 * method joins on the same content.
 */
public final class FuzzyJoinColumn implements JoinMethod {

    private static final String SEP = " ";

    @Override
    public String name() { return "FJ-C"; }

    @Override
    public List<Row[]> join(JoinInput in) {
        List<String> srcVals = new ArrayList<>(in.source.numRows());
        for (int i = 0; i < in.source.numRows(); i++) {
            srcVals.add(JoinMethod.joinValue(in.source.getRow(i), in.srcKeyCols, SEP));
        }
        List<String> tgtVals = new ArrayList<>(in.target.numRows());
        for (int j = 0; j < in.target.numRows(); j++) {
            tgtVals.add(JoinMethod.joinValue(in.target.getRow(j), in.tgtKeyCols, SEP));
        }

        List<FuzzyJoinResult> matches =
                new ConstrainedFuzzyJoin().executeJoin(srcVals, tgtVals);

        List<Row[]> pairs = new ArrayList<>(matches.size());
        for (FuzzyJoinResult m : matches) {
            pairs.add(new Row[]{
                    in.source.getRow(m.sourceRowIndex),
                    in.target.getRow(m.targetRowIndex)});
        }
        return pairs;
    }
}
