package com.autojoin.baselines;

import com.autojoin.fuzzy_join.ConstrainedFuzzyJoin;
import com.autojoin.fuzzy_join.FuzzyJoinResult;
import com.autojoin.model.Row;
import com.autojoin.model.Table;

import java.util.ArrayList;
import java.util.List;

/**
 * FJ-FR — Fuzzy Join, Full Row (paper §6.2).
 *
 * "This fuzzy join variant is similar to FJ-C, but we do not provide the
 * information on which columns are used in join in the ground truth. As a
 * result, this approach considers full rows in each table. This represents a
 * realistic scenario of how fuzzy join would be used without ground truth."
 *
 * Identical to {@link FuzzyJoinColumn} except each row's join value is the
 * concatenation of ALL its columns, not just the known key columns. Note the
 * paper omits FJ-FR from the §6.4 DBLP scalability run because there the
 * target is already a full-row concatenation, making FJ-FR identical to FJ-C
 * — it only differentiates on the quality benchmarks.
 */
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
