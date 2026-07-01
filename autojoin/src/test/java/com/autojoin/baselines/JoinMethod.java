package com.autojoin.baselines;

import com.autojoin.model.Row;
import com.autojoin.model.Table;

import java.util.List;

/**
 * A baseline join method for the comparison against Auto-Join (paper §6.2).
 *
 * Each method receives the two tables plus the key columns that participate in
 * the join (the paper gives SM/FJ-O/FJ-C "the columns that are used in the
 * ground truth as if they are known a priori"), and returns the joined
 * (sourceRow, targetRow) pairs — always source-first, so the surrounding
 * precision/recall harness can score them the same way it scores Auto-Join.
 */
public interface JoinMethod {

    /** Short label used in comparison tables (e.g. "SM", "FJ-C", "FJ-O"). */
    String name();

    /** Produce joined (sourceRow, targetRow) pairs for one case. */
    List<Row[]> join(JoinInput in);

    /** Inputs shared by every baseline. */
    final class JoinInput {
        public final Table source;
        public final Table target;
        public final List<String> srcKeyCols;
        public final List<String> tgtKeyCols;

        public JoinInput(Table source, Table target,
                         List<String> srcKeyCols, List<String> tgtKeyCols) {
            this.source = source;
            this.target = target;
            this.srcKeyCols = srcKeyCols;
            this.tgtKeyCols = tgtKeyCols;
        }
    }

    /**
     * Positional join value for one row: the row's key-column values joined with
     * {@code sep}. Uses positional lookup so duplicate key-column names (some
     * benchmark targets repeat a header) stay distinct, matching how the
     * precision/recall harness fingerprints rows.
     */
    static String joinValue(Row row, List<String> keyCols, String sep) {
        List<String> rowCols = row.getColumnNames();
        boolean[] used = new boolean[rowCols.size()];
        StringBuilder sb = new StringBuilder();
        for (int c = 0; c < keyCols.size(); c++) {
            if (c > 0) sb.append(sep);
            String key = keyCols.get(c);
            int idx = -1;
            for (int i = 0; i < rowCols.size(); i++) {
                if (!used[i] && rowCols.get(i).equals(key)) { idx = i; break; }
            }
            String v = idx >= 0 ? row.get(idx) : row.get(key);
            sb.append(v == null ? "" : v);
        }
        return sb.toString();
    }
}
