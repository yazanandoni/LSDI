package com.autojoin.baselines;

import com.autojoin.fuzzy_join.ConstrainedFuzzyJoin;
import com.autojoin.fuzzy_join.FuzzyJoinResult;
import com.autojoin.model.Row;
import com.autojoin.model.Table;

import java.util.ArrayList;
import java.util.List;

public final class FuzzyJoinFullRow implements JoinMethod {

    private static final String SEP = " ";

    @Override
    public String name() { return "FJ-FR"; }

    @Override
    public List<Row[]> join(JoinInput in) {
        List<String> srcVals = fullRowValues(in.source);
        List<String> tgtVals = fullRowValues(in.target);

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

    private static List<String> fullRowValues(Table table) {
        List<String> vals = new ArrayList<>(table.numRows());
        for (int i = 0; i < table.numRows(); i++) {
            Row row = table.getRow(i);
            StringBuilder sb = new StringBuilder();
            for (int c = 0; c < row.size(); c++) {
                if (c > 0) sb.append(SEP);
                String v = row.get(c);
                sb.append(v == null ? "" : v);
            }
            vals.add(sb.toString());
        }
        return vals;
    }
}
