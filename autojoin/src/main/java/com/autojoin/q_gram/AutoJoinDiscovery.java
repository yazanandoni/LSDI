package com.autojoin.q_gram;

import com.autojoin.model.Column;
import com.autojoin.model.Table;

import java.util.*;

public class AutoJoinDiscovery {

    public List<ColumnPairMatches> findJoinableRowPairs(Table sourceTable, Table targetTable) {
        Map<String, List<MatchResult>> groupedMatches = new HashMap<>();

        // Iterate over all columns in the source table
        for (Column cs : sourceTable.getColumns()) {
            ColumnSuffixIndex sourceIdx = new ColumnSuffixIndex(cs.getValues());

            // Iterate over all KEY columns in the target table
            for (Column ct : targetTable.getKeyColumns()) {
                ColumnSuffixIndex targetIdx = new ColumnSuffixIndex(ct.getValues());

                String groupKey = cs.getName() + "|" + ct.getName();
                groupedMatches.putIfAbsent(groupKey, new ArrayList<>());

                // Iterate over DISTINCT values in the source column
                Set<String> distinctValues = new HashSet<>(cs.getValues());
                for (String v : distinctValues) {
                    if (v == null || v.isEmpty()) continue;

                    QGramFinder matcher = new QGramFinder();
                    MatchResult result = matcher.findOptimalQGram(v, sourceIdx, targetIdx);

                    if (result != null && result.score > 0) {
                        groupedMatches.get(groupKey).add(result);
                    }
                }
            }
        }

        // Sort the matches in each group highest-to-lowest
        List<ColumnPairMatches> finalResults = new ArrayList<>();
        for (Map.Entry<String, List<MatchResult>> entry : groupedMatches.entrySet()) {
            List<MatchResult> matches = entry.getValue();

            // Sort descending by score
            matches.sort((a, b) -> Double.compare(b.score, a.score));

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
