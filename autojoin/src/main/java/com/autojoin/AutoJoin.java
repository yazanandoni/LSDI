package com.autojoin;

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
import com.autojoin.trace.DirectionTrace;
import com.autojoin.trace.DiscoveryTrace;
import com.autojoin.trace.ExamplePairData;
import com.autojoin.trace.LearningTrace;
import com.autojoin.trace.OperatorNode;
import com.autojoin.trace.QGramMatch;
import com.autojoin.trace.SampleMatch;
import com.autojoin.trace.TransformStep;

import java.util.ArrayList;
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
        DirectionResult forward = tryDirection(ts, tt);
        DirectionResult backward = tryDirection(tt, ts);

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

        AlgorithmTrace trace = new AlgorithmTrace(forwardWon, forward.trace, backward.trace);
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

        return new LearningTrace(
                learned.sourceColumnName,
                learned.targetColumnName,
                learned.score,
                sourceTable.numRows(),
                examplePairs,
                operatorNodes,
                demoInput, demoTarget,
                transformDemo);
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

        ApplicationTrace applicationTrace = buildApplicationTrace(joinedPairs, learned, sourceTable);

        DirectionTrace directionTrace = new DirectionTrace(discoveryTrace, learningTrace, applicationTrace);

        return new DirectionResult(
                JoinResult.of(joinedPairs, learned.program.describe()),
                learned.score,
                directionTrace);
    }
}
