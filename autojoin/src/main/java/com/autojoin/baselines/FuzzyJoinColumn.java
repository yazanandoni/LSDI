package com.autojoin.baselines;

import com.autojoin.fuzzy_join.ConstrainedFuzzyJoin;
import com.autojoin.fuzzy_join.FuzzyJoinResult;
import com.autojoin.model.Row;

import java.util.ArrayList;
import java.util.List;

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
