package com.autojoin;

import com.autojoin.fuzzy_join.ConstrainedFuzzyJoin;
import com.autojoin.fuzzy_join.FuzzyJoinResult;
import com.autojoin.model.Column;
import com.autojoin.model.Row;
import com.autojoin.model.Table;
import com.autojoin.operator.ConstantOp;
import com.autojoin.operator.LogicalOperator;
import com.autojoin.operator.SplitSplitSubstrOp;
import com.autojoin.operator.SplitSubstrOp;
import com.autojoin.operator.SubstrOp;
import com.autojoin.q_gram.AutoJoinDiscovery;
import com.autojoin.q_gram.ColumnPairMatches;
import com.autojoin.q_gram.MatchResult;
import com.autojoin.synthesis.TransformationLearner;
import com.autojoin.synthesis.TransformationLearner.LearnedTransformation;
import com.autojoin.trace.AlgorithmTrace;
import com.autojoin.trace.ApplicationTrace;
import com.autojoin.trace.ColumnPairGroup;
import com.autojoin.trace.DemoMatch;
import com.autojoin.trace.DirectionTrace;
import com.autojoin.trace.DiscoveryTrace;
import com.autojoin.trace.ExamplePairData;
import com.autojoin.trace.InputTablesTrace;
import com.autojoin.trace.InputTablesTrace.TableInfo;
import com.autojoin.trace.LearningTrace;
import com.autojoin.trace.OperatorNode;
import com.autojoin.trace.QGramMatch;
import com.autojoin.trace.SampleMatch;
import com.autojoin.trace.TransformStep;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public class AutoJoin {

    private final AutoJoinDiscovery discovery = new AutoJoinDiscovery();
    private final TransformationLearner learner = new TransformationLearner();

    public JoinResult join(Table ts, Table tt) {
        // The two directions are fully independent (discovery is stateless and
        // the learner creates per-group synthesizers), so run them
        // concurrently: wall time becomes max(forward, backward) instead of
        // the sum. The backward attempt runs on this thread so a 2-direction
        // join doesn't need a second pool thread beyond the forward task.
        java.util.concurrent.CompletableFuture<DirectionResult> forwardFuture =
                java.util.concurrent.CompletableFuture.supplyAsync(() -> tryDirection(ts, tt));
        DirectionResult backward = tryDirection(tt, ts);
        DirectionResult forward = forwardFuture.join();

        boolean forwardWon;
        JoinResult bestJoin;

        if (forward.join.isEmpty() && backward.join.isEmpty()) return JoinResult.empty();
        if (forward.join.isEmpty()) {
            forwardWon = false;
            bestJoin = orientSourceTarget(backward.join);
        } else if (backward.join.isEmpty()) {
            forwardWon = true;
            bestJoin = forward.join;
        } else {
            if (forward.score != backward.score) {
                forwardWon = forward.score > backward.score;
                bestJoin = forwardWon ? forward.join : orientSourceTarget(backward.join);
            } else {
                forwardWon = forward.join.size() >= backward.join.size();
                bestJoin = forwardWon ? forward.join : orientSourceTarget(backward.join);
            }
        }

        InputTablesTrace inputTables = buildInputTablesTrace(ts, tt);
        AlgorithmTrace trace = new AlgorithmTrace(forwardWon, forward.trace, backward.trace, inputTables);
        return JoinResult.of(bestJoin.getJoinedPairs(), bestJoin.getTransformationDescription(), trace);
    }

    private static JoinResult orientSourceTarget(JoinResult backward) {
        List<Row[]> swapped = new ArrayList<>(backward.size());
        for (Row[] pair : backward.getJoinedPairs()) {
            swapped.add(new Row[]{pair[1], pair[0]});
        }
        return JoinResult.of(swapped, backward.getTransformationDescription());
    }

    private static final class DirectionResult {
        final JoinResult join;
        final int score;
        final DirectionTrace trace;

        DirectionResult(JoinResult join, int score, DirectionTrace trace) {
            this.join = join;
            this.score = score;
            this.trace = trace;
        }

        static DirectionResult empty() {
            return new DirectionResult(JoinResult.empty(), 0, DirectionTrace.empty());
        }
    }

    private static InputTablesTrace buildInputTablesTrace(Table source, Table target) {
        List<String> srcCols = source.getColumns().stream().map(Column::getName).toList();
        List<String> tgtCols = target.getColumns().stream().map(Column::getName).toList();
        List<String> srcKeys = source.getKeyColumns().stream().map(Column::getName).toList();
        List<String> tgtKeys = target.getKeyColumns().stream().map(Column::getName).toList();

        List<List<String>> srcSamples = new ArrayList<>();
        int srcLimit = Math.min(5, source.numRows());
        for (int i = 0; i < srcLimit; i++) {
            Row row = source.getRow(i);
            List<String> vals = new ArrayList<>();
            for (String col : srcCols) vals.add(row.get(col));
            srcSamples.add(vals);
        }

        List<List<String>> tgtSamples = new ArrayList<>();
        int tgtLimit = Math.min(5, target.numRows());
        for (int i = 0; i < tgtLimit; i++) {
            Row row = target.getRow(i);
            List<String> vals = new ArrayList<>();
            for (String col : tgtCols) vals.add(row.get(col));
            tgtSamples.add(vals);
        }

        return new InputTablesTrace(
                new TableInfo("Source", source.numRows(), source.numColumns(),
                        srcCols, srcKeys, srcSamples),
                new TableInfo("Target", target.numRows(), target.numColumns(),
                        tgtCols, tgtKeys, tgtSamples));
    }

    private static DiscoveryTrace buildDiscoveryTrace(List<ColumnPairMatches> matches,
                                                       Table sourceTable, Table targetTable) {
        List<ColumnPairGroup> groups = new ArrayList<>();
        for (ColumnPairMatches group : matches) {
            String srcCol = group.getSourceColumnName();
            String tgtCol = group.getTargetColumnName();
            List<MatchResult> matchResults = group.getMatches();

            double avgScore = matchResults.stream()
                    .mapToDouble(MatchResult::getScore)
                    .average().orElse(0);

            int topN = Math.min(5, matchResults.size());
            List<QGramMatch> topMatches = new ArrayList<>();
            for (int i = 0; i < topN; i++) {
                MatchResult mr = matchResults.get(i);
                List<Integer> srcRows = mr.getBestSourceRows();
                List<Integer> tgtRows = mr.getBestTargetRows();
                if (srcRows.isEmpty() || tgtRows.isEmpty()) continue;
                String srcVal = sourceTable.getRow(srcRows.get(0)).get(srcCol);
                String tgtVal = targetTable.getRow(tgtRows.get(0)).get(tgtCol);
                topMatches.add(new QGramMatch(srcVal, mr.getQGram(), tgtVal, mr.getScore()));
            }

            groups.add(new ColumnPairGroup(srcCol, tgtCol, matchResults.size(), avgScore, topMatches));
        }
        return new DiscoveryTrace(groups);
    }

    private static LearningTrace buildLearningTrace(List<ColumnPairMatches> matches,
                                                     LearnedTransformation learned,
                                                     Table sourceTable, Table targetTable) {
        List<ExamplePairData> examplePairs = List.of();
        List<Integer> exampleRowIndices = new ArrayList<>();
        String demoInput = "";
        String demoTarget = "";
        int demoRowIndex = -1;
        for (ColumnPairMatches group : matches) {
            if (group.getSourceColumnName().equals(learned.sourceColumnName)
                    && group.getTargetColumnName().equals(learned.targetColumnName)) {
                int topN = Math.min(6, group.getMatches().size());
                examplePairs = new ArrayList<>();
                for (int i = 0; i < topN; i++) {
                    MatchResult mr = group.getMatches().get(i);
                    List<Integer> srcRows = mr.getBestSourceRows();
                    List<Integer> tgtRows = mr.getBestTargetRows();
                    if (srcRows.isEmpty() || tgtRows.isEmpty()) continue;
                    String srcVal = sourceTable.getRow(srcRows.get(0)).get(learned.sourceColumnName);
                    String tgtVal = targetTable.getRow(tgtRows.get(0)).get(learned.targetColumnName);
                    examplePairs.add(new ExamplePairData(srcVal, tgtVal));
                    exampleRowIndices.add(srcRows.get(0));
                }
                if (!group.getMatches().isEmpty()) {
                    MatchResult first = group.getMatches().get(0);
                    List<Integer> srcRows = first.getBestSourceRows();
                    List<Integer> tgtRows = first.getBestTargetRows();
                    if (!srcRows.isEmpty()) {
                        demoRowIndex = srcRows.get(0);
                        demoInput = sourceTable.getRow(demoRowIndex).get(learned.sourceColumnName);
                    }
                    if (!tgtRows.isEmpty()) {
                        demoTarget = targetTable.getRow(tgtRows.get(0)).get(learned.targetColumnName);
                    }
                }
                break;
            }
        }

        List<OperatorNode> operatorNodes = new ArrayList<>();
        for (LogicalOperator op : learned.program.getOperators()) {
            operatorNodes.add(toOperatorNode(op));
        }

        List<TransformStep> transformDemo = new ArrayList<>();
        if (demoRowIndex >= 0 && !learned.program.getOperators().isEmpty()) {
            String[] rowArr = sourceTable.getRow(demoRowIndex).getValues().toArray(new String[0]);
            StringBuilder accumulated = new StringBuilder();
            for (LogicalOperator op : learned.program.getOperators()) {
                String output = op.apply(rowArr);
                if (output != null) accumulated.append(output);
                OperatorNode opNode = toOperatorNode(op);
                transformDemo.add(new TransformStep(
                        opNode.getType(), opNode.getDescription(),
                        opNode.getParams(), accumulated.toString()));
            }
        }

        List<DemoMatch> demoMatches = new ArrayList<>();
        if (!learned.program.getOperators().isEmpty() && exampleRowIndices.size() > 1) {
            int limit = Math.min(4, exampleRowIndices.size());
            for (int i = 1; i < limit; i++) {
                int rowIdx = exampleRowIndices.get(i);
                String[] rowArr = sourceTable.getRow(rowIdx).getValues().toArray(new String[0]);
                String key = learned.program.apply(rowArr);
                String transformed = key != null ? key : "";
                ExamplePairData pair = examplePairs.get(i);
                demoMatches.add(new DemoMatch(
                        pair.getSourceValue(), transformed,
                        pair.getTargetValue(),
                        transformed.equals(pair.getTargetValue())));
            }
        }

        return new LearningTrace(
                learned.sourceColumnName,
                learned.targetColumnName,
                learned.score,
                sourceTable.numRows(),
                examplePairs,
                operatorNodes,
                demoInput, demoTarget,
                transformDemo,
                demoMatches);
    }

    private static OperatorNode toOperatorNode(LogicalOperator op) {
        Map<String, String> params = new LinkedHashMap<>();
        String type;
        String description = op.describe();

        if (op instanceof ConstantOp c) {
            type = "Constant";
            params.put("value", c.getValue());
        } else if (op instanceof SubstrOp s) {
            type = "Substr";
            params.put("k", String.valueOf(s.getK()));
            params.put("start", String.valueOf(s.getStart()));
            params.put("length", String.valueOf(s.getLength()));
            params.put("casing", s.getCasing().name());
        } else if (op instanceof SplitSubstrOp s) {
            type = "SplitSubstr";
            params.put("k", String.valueOf(s.getK()));
            params.put("separator", s.getSep());
            params.put("part", String.valueOf(s.getM()));
            params.put("start", String.valueOf(s.getStart()));
            params.put("length", String.valueOf(s.getLength()));
            params.put("casing", s.getCasing().name());
        } else if (op instanceof SplitSplitSubstrOp s) {
            type = "SplitSplitSubstr";
            params.put("k1", String.valueOf(s.getK1()));
            params.put("separator1", s.getSep1());
            params.put("k2", String.valueOf(s.getK2()));
            params.put("separator2", s.getSep2());
            params.put("part", String.valueOf(s.getM()));
            params.put("start", String.valueOf(s.getStart()));
            params.put("length", String.valueOf(s.getLength()));
            params.put("casing", s.getCasing().name());
        } else {
            type = "Unknown";
        }

        return new OperatorNode(type, description, params);
    }

    private static ApplicationTrace buildApplicationTrace(List<Row[]> joinedPairs,
                                                           LearnedTransformation learned,
                                                           Table sourceTable) {
        Set<String> matchedValues = new HashSet<>();
        List<SampleMatch> samples = new ArrayList<>();

        for (Row[] pair : joinedPairs) {
            Row srcRow = pair[0];
            Row tgtRow = pair[1];
            String srcVal = safeGet(srcRow, learned.sourceColumnName);
            String tgtVal = safeGet(tgtRow, learned.targetColumnName);
            String[] rowArr = srcRow.getValues().toArray(new String[0]);
            String key = learned.program.apply(rowArr);
            if (samples.size() < 5) {
                samples.add(new SampleMatch(srcVal, key != null ? key : "", tgtVal, "MATCHED"));
            }
            matchedValues.add(srcVal);
        }

        int addedUnmatched = 0;
        int unmatchedCount = 0;
        for (int i = 0; i < sourceTable.numRows(); i++) {
            Row row = sourceTable.getRow(i);
            String srcVal = safeGet(row, learned.sourceColumnName);
            if (!matchedValues.contains(srcVal)) {
                unmatchedCount++;
                if (addedUnmatched < 3) {
                    String[] rowArr = row.getValues().toArray(new String[0]);
                    String key = learned.program.apply(rowArr);
                    samples.add(new SampleMatch(srcVal, key != null ? key : "", "", "UNMATCHED"));
                    addedUnmatched++;
                }
            }
        }

        return new ApplicationTrace(
                sourceTable.numRows(),
                joinedPairs.size(),
                unmatchedCount,
                samples);
    }

    private static String safeGet(Row row, String columnName) {
        if (row == null || columnName == null) return "";
        String val = row.get(columnName);
        return val != null ? val : "";
    }

    private DirectionResult tryDirection(Table sourceTable, Table targetTable) {
        boolean debug = Boolean.getBoolean("autojoin.debug");
        if (debug) System.err.printf("[direction] %s (%d rows) -> %s (%d rows)%n",
                sourceTable.getName(), sourceTable.numRows(),
                targetTable.getName(), targetTable.numRows());

        long t0 = System.nanoTime();
        List<ColumnPairMatches> matches =
                discovery.findJoinableRowPairs(sourceTable, targetTable);
        if (debug) System.err.printf("  [discovery] %d groups in %dms%n",
                matches.size(), (System.nanoTime() - t0) / 1_000_000);

        DiscoveryTrace discoveryTrace = buildDiscoveryTrace(matches, sourceTable, targetTable);

        if (matches.isEmpty()) return DirectionResult.empty();

        long t1 = System.nanoTime();
        LearnedTransformation learned = learner.learn(matches, sourceTable, targetTable);
        if (debug) System.err.printf("  [learn] total %dms%n", (System.nanoTime() - t1) / 1_000_000);

        if (learned == null) return DirectionResult.empty();

        LearningTrace learningTrace = buildLearningTrace(matches, learned, sourceTable, targetTable);

        List<Row[]> joinedPairs = TransformationLearner.applyJoin(
                learned.program, sourceTable, targetTable, learned.targetColumnName);

        if (joinedPairs.isEmpty()) return DirectionResult.empty();

        // Phase 4 (paper §5): constrained fuzzy join recovery of the rows the
        // strict equi-join left unmatched.
        List<Row[]> recoveredPairs = fuzzyRecover(learned, sourceTable, targetTable);
        List<Row[]> allPairs = joinedPairs;
        if (!recoveredPairs.isEmpty()) {
            allPairs = new ArrayList<>(joinedPairs);
            allPairs.addAll(recoveredPairs);
        }
        if (debug) System.err.printf("  [fuzzy] recovered %d of unmatched rows%n", recoveredPairs.size());

        ApplicationTrace applicationTrace = buildApplicationTrace(allPairs, learned, sourceTable);

        DirectionTrace directionTrace = new DirectionTrace(discoveryTrace, learningTrace, applicationTrace);

        return new DirectionResult(
                JoinResult.of(allPairs, learned.program.describe()),
                learned.score,
                directionTrace);
    }

    /**
     * §5 constrained fuzzy join recovery. The transformation equi-join only
     * emits clean 1:1 matches, so rows whose derived key narrowly misses the
     * target (e.g. formatting noise like a trailing space) stay unmatched even
     * when the transform is right. This pass fuzzy-matches ONLY those leftover
     * source rows against ONLY the leftover target rows, with the distance
     * threshold auto-tuned over the full columns — already-joined values
     * constrain it, so no equi-joined row can gain a second (wrong) match and
     * ambiguous matches the equi-join deliberately dropped are not re-admitted.
     */
    private static List<Row[]> fuzzyRecover(LearnedTransformation learned,
                                            Table sourceTable, Table targetTable) {
        Optional<Column> tgtColOpt = targetTable.getColumn(learned.targetColumnName);
        if (tgtColOpt.isEmpty()) return List.of();
        List<String> targetVals = tgtColOpt.get().getValues();

        // Derive every source key once.
        int n = sourceTable.numRows();
        List<String> derived = new ArrayList<>(n);
        Map<String, Integer> derivedCounts = new HashMap<>();
        for (int i = 0; i < n; i++) {
            String[] rowArr = sourceTable.getRow(i).getValues().toArray(new String[0]);
            String d = learned.program.apply(rowArr);
            derived.add(d);
            if (d != null) derivedCounts.merge(d, 1, Integer::sum);
        }
        Map<String, Integer> targetValueCounts = new HashMap<>();
        for (String v : targetVals) {
            targetValueCounts.merge(v, 1, Integer::sum);
        }

        // Mirror applyJoin's emission criterion (unique target key; source-side
        // N:1 allowed) to flag the rows the equi-join already covered.
        boolean[] srcMatched = new boolean[n];
        boolean anyUnmatchedSrc = false;
        for (int i = 0; i < n; i++) {
            String d = derived.get(i);
            srcMatched[i] = d != null
                    && Integer.valueOf(1).equals(targetValueCounts.get(d));
            if (!srcMatched[i]) anyUnmatchedSrc = true;
        }
        boolean[] tgtMatched = new boolean[targetVals.size()];
        boolean anyUnmatchedTgt = false;
        for (int j = 0; j < targetVals.size(); j++) {
            String v = targetVals.get(j);
            tgtMatched[j] = v != null
                    && targetValueCounts.get(v) == 1
                    && derivedCounts.getOrDefault(v, 0) >= 1;
            if (!tgtMatched[j]) anyUnmatchedTgt = true;
        }
        if (!anyUnmatchedSrc || !anyUnmatchedTgt) return List.of();

        List<FuzzyJoinResult> recovered = new ConstrainedFuzzyJoin()
                .recoverUnmatched(derived, targetVals, srcMatched, tgtMatched);

        List<Row[]> pairs = new ArrayList<>(recovered.size());
        for (FuzzyJoinResult r : recovered) {
            pairs.add(new Row[]{
                    sourceTable.getRow(r.sourceRowIndex),
                    targetTable.getRow(r.targetRowIndex)});
        }
        return pairs;
    }
}
