package com.autojoin.baselines;

import com.autojoin.model.Row;
import com.autojoin.model.Table;

import java.util.List;

public interface JoinMethod {

    String name();

    List<Row[]> join(JoinInput in);

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
