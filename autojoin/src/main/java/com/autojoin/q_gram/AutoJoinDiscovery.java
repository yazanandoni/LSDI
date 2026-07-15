package com.autojoin.q_gram;

import com.autojoin.model.Column;
import com.autojoin.model.Table;

import java.util.*;

public class AutoJoinDiscovery {

    private static final int MAX_MATCH_PRODUCT = 1;

    private static final int MIN_QGRAM_LENGTH = 3;

    public List<ColumnPairMatches> findJoinableRowPairs(Table sourceTable, Table targetTable) {
        Map<String, List<MatchResult>> groupedMatches = new HashMap<>();
        QGramFinder matcher = new QGramFinder();

        List<Column> targetKeyColumns = targetTable.getKeyColumns();
        List<ColumnSuffixIndex> targetIndexes = new ArrayList<>(targetKeyColumns.size());
        for (Column ct : targetKeyColumns) {
            targetIndexes.add(new ColumnSuffixIndex(ct.getValues()));
        }

        for (Column cs : sourceTable.getColumns()) {
            ColumnSuffixIndex sourceIdx = new ColumnSuffixIndex(cs.getValues());

            for (int t = 0; t < targetKeyColumns.size(); t++) {
                Column ct = targetKeyColumns.get(t);
                ColumnSuffixIndex targetIdx = targetIndexes.get(t);

                String groupKey = cs.getName() + "|" + ct.getName();
                groupedMatches.putIfAbsent(groupKey, new ArrayList<>());

                Set<String> distinctValues = new HashSet<>(cs.getValues());
                for (String v : distinctValues) {
                    if (v == null || v.isEmpty()) continue;

                    MatchResult result = matcher.findOptimalQGram(v, sourceIdx, targetIdx);

                    if (result != null && result.getQGram() != null
                            && result.getQGram().length() >= MIN_QGRAM_LENGTH
                            && result.getMatchProduct() <= MAX_MATCH_PRODUCT) {
                        groupedMatches.get(groupKey).add(result);
                    }
                }
            }
        }

        List<ColumnPairMatches> finalResults = new ArrayList<>();
        for (Map.Entry<String, List<MatchResult>> entry : groupedMatches.entrySet()) {
            List<MatchResult> matches = entry.getValue();

            matches.sort((a, b) -> {
                int byScore = Double.compare(b.score, a.score);
                if (byScore != 0) return byScore;
                int lenA = a.getQGram() == null ? 0 : a.getQGram().length();
                int lenB = b.getQGram() == null ? 0 : b.getQGram().length();
                return Integer.compare(lenB, lenA);
            });

            String[] cols = entry.getKey().split("\\|");
            ColumnPairMatches group = new ColumnPairMatches();
            group.sourceColumnName = cols[0];
            group.targetColumnName = cols[1];
            group.matches = matches;

            finalResults.add(group);
        }

        return finalResults;
    }
}
