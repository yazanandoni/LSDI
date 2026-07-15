package com.autojoin;

import com.autojoin.fuzzy_join.ConstrainedFuzzyJoin;
import com.autojoin.fuzzy_join.ConstrainedFuzzyJoin.RecoveryOutcome;
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
import com.autojoin.sampling.Sample;
import com.autojoin.split.CompositeColumnSplitter;
import com.autojoin.synthesis.TransformationLearner;
import com.autojoin.synthesis.TransformationLearner.LearnedTransformation;
import com.autojoin.synthesis.TransformationProgram;
import com.autojoin.trace.AlgorithmTrace;
import com.autojoin.trace.ApplicationTrace;
import com.autojoin.trace.ColumnPairGroup;
import com.autojoin.trace.DemoMatch;
import com.autojoin.trace.DirectionTrace;
import com.autojoin.trace.DiscoveryTrace;
import com.autojoin.trace.ExamplePairData;
import com.autojoin.trace.FuzzyRecoveryMatch;
import com.autojoin.trace.FuzzyTrace;
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

    private final boolean fuzzyJoinEnabled;

    public AutoJoin() {
        this(true);
    }

    public AutoJoin(boolean fuzzyJoinEnabled) {
        this.fuzzyJoinEnabled = fuzzyJoinEnabled;
    }

    private static final int MAX_SPLIT_RETRY_ROWS = 1000;

    public JoinResult join(Table ts, Table tt) {

        java.util.concurrent.CompletableFuture<DirectionResult> forwardFuture =
                java.util.concurrent.CompletableFuture.supplyAsync(() -> tryDirection(ts, tt));
        DirectionResult backward = tryDirection(tt, ts);
        DirectionResult forward = forwardFuture.join();

        List<Candidate> candidates = new ArrayList<>(4);
        candidates.add(new Candidate(forward, true));
        candidates.add(new Candidate(backward, false));

        if (forward.score < 1 && backward.score < 1
                && Math.max(ts.numRows(), tt.numRows()) <= MAX_SPLIT_RETRY_ROWS) {
            CompositeColumnSplitter splitter = new CompositeColumnSplitter();
            Table tsSplit = splitter.splitKeyColumns(ts);
            Table ttSplit = splitter.splitKeyColumns(tt);
            if (tsSplit != ts || ttSplit != tt) {
                java.util.concurrent.CompletableFuture<DirectionResult> fSplitFuture =
                        java.util.concurrent.CompletableFuture.supplyAsync(() -> tryDirection(tsSplit, ttSplit));
                DirectionResult bSplit = tryDirection(ttSplit, tsSplit);
                DirectionResult fSplit = fSplitFuture.join();
                if (fSplit.score >= 1) candidates.add(new Candidate(fSplit, true));
                if (bSplit.score >= 1) candidates.add(new Candidate(bSplit, false));
            }
        }

        Candidate winner = pickBest(candidates);
        if (winner == null) return JoinResult.empty();

        boolean forwardWon = winner.forward();
        JoinResult bestJoin = forwardWon ? winner.dr().join : orientSourceTarget(winner.dr().join);

        InputTablesTrace inputTables = buildInputTablesTrace(ts, tt);
        AlgorithmTrace trace = new AlgorithmTrace(forwardWon, forward.trace, backward.trace, inputTables,
                winner.dr().discoveryMs, winner.dr().learningMs,
                winner.dr().joinMs, winner.dr().fuzzyMs);
        return JoinResult.of(bestJoin.getJoinedPairs(), bestJoin.getTransformationDescription(), trace);
    }

    /** A directional result and whether it is already source→target oriented. */
    private record Candidate(DirectionResult dr, boolean forward) {}

    private static Candidate pickBest(List<Candidate> candidates) {
        Candidate best = null;
        for (Candidate c : candidates) {
            if (c.dr().join.isEmpty()) continue;
            if (best == null) { best = c; continue; }
            int cmp = Integer.compare(c.dr().score, best.dr().score);
            if (cmp == 0) cmp = Integer.compare(c.dr().keyColumns, best.dr().keyColumns);
            if (cmp == 0) cmp = Integer.compare(c.dr().join.size(), best.dr().join.size());
            if (cmp > 0) best = c;
        }
        return best;
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
        /** Distinct source columns the chosen transform reads — the tie-breaker
         *  when two directions score equally (richer key preferred). */
        final int keyColumns;
        final DirectionTrace trace;
        final long discoveryMs;
        final long learningMs;
        final long joinMs;
        final long fuzzyMs;

        DirectionResult(JoinResult join, int score, int keyColumns, DirectionTrace trace,
                        long discoveryMs, long learningMs, long joinMs, long fuzzyMs) {
            this.join = join;
            this.score = score;
            this.keyColumns = keyColumns;
            this.trace = trace;
            this.discoveryMs = discoveryMs;
            this.learningMs = learningMs;
            this.joinMs = joinMs;
            this.fuzzyMs = fuzzyMs;
        }

        static DirectionResult empty() {
            return new DirectionResult(JoinResult.empty(), 0, 0, DirectionTrace.empty(),
                    0, 0, 0, 0);
        }
    }

    private static int distinctSourceColumns(TransformationProgram program) {
        Set<Integer> cols = new HashSet<>();
        for (LogicalOperator op : program.getOperators()) {
            if (op instanceof SubstrOp s) cols.add(s.getK());
            else if (op instanceof SplitSubstrOp s) cols.add(s.getK());
            else if (op instanceof SplitSplitSubstrOp s) cols.add(s.getK1());
        }
        return cols.size();
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

        double t = 4;
        double delta = 0.8;
        double r = 0.1;
        Sample.SampleResult sample = Sample.sampleTables(sourceTable, targetTable, t, delta, r);
        Table sourceSample = sample.sourceSample();
        Table targetSample = sample.targetSample();

        List<ColumnPairMatches> matches =
                discovery.findJoinableRowPairs(sourceSample, targetSample);
        long t1 = System.nanoTime();
        long discoveryMs = (t1 - t0) / 1_000_000;
        if (debug) System.err.printf("  [discovery] %d groups in %dms%n",
                matches.size(), (System.nanoTime() - t0) / 1_000_000);

        DiscoveryTrace discoveryTrace = buildDiscoveryTrace(matches, sourceTable, targetTable);

        if (matches.isEmpty()) return DirectionResult.empty();

        long t1L = System.nanoTime();
        LearnedTransformation learned = learner.learn(matches, sourceSample, targetSample);
        long learningMs = (System.nanoTime() - t1L) / 1_000_000;
        if (debug) System.err.printf("  [learn] total %dms%n", (System.nanoTime() - t1) / 1_000_000);

        if (learned == null) {
            if (!fuzzyJoinEnabled) return DirectionResult.empty();
            DirectionResult fuzzyOnly = fuzzyOnlyDirection(matches, sourceTable, targetTable);
            if (debug) System.err.printf("  [fuzzy-only] %d pairs (no transform learned)%n",
                    fuzzyOnly.join.size());
            return fuzzyOnly;
        }

        LearningTrace learningTrace = buildLearningTrace(matches, learned, sourceTable, targetTable);

                long t2 = System.nanoTime();
        List<Row[]> joinedPairs = TransformationLearner.applyJoin(
                learned.program, sourceTable, targetTable, learned.targetColumnName);
        long joinMs = (System.nanoTime() - t2) / 1_000_000;

        if (joinedPairs.isEmpty()) return DirectionResult.empty();

        ApplicationTrace applicationTrace = buildApplicationTrace(
                joinedPairs, learned, sourceTable);

        long t3 = System.nanoTime();
        FuzzyRecoveryResult fuzzyResult = fuzzyJoinEnabled
                ? fuzzyRecover(learned, sourceTable, targetTable)
                : new FuzzyRecoveryResult(List.of(), List.of(), 0.0, 0);
        long fuzzyMs = (System.nanoTime() - t3) / 1_000_000;
        List<Row[]> allPairs = joinedPairs;
        int unmatchedBefore = fuzzyResult.unmatchedBeforeFuzzy();
        FuzzyTrace fuzzyTrace;
        if (!fuzzyResult.pairs().isEmpty()) {
            allPairs = new ArrayList<>(joinedPairs);
            allPairs.addAll(fuzzyResult.pairs());

            List<FuzzyRecoveryMatch> samples = new ArrayList<>();
            int sampleLimit = Math.min(5, fuzzyResult.rawResults().size());
            for (int i = 0; i < sampleLimit; i++) {
                FuzzyJoinResult fr = fuzzyResult.rawResults().get(i);
                String srcVal = safeGet(sourceTable.getRow(fr.sourceRowIndex), learned.sourceColumnName);
                String tgtVal = safeGet(targetTable.getRow(fr.targetRowIndex), learned.targetColumnName);
                samples.add(new FuzzyRecoveryMatch(srcVal, tgtVal, fr.distance));
            }
            int remaining = unmatchedBefore - fuzzyResult.pairs().size();
            fuzzyTrace = new FuzzyTrace(
                    fuzzyResult.pairs().size(),
                    fuzzyResult.threshold(),
                    unmatchedBefore,
                    remaining,
                    samples);
        } else if (unmatchedBefore > 0) {
            fuzzyTrace = new FuzzyTrace(unmatchedBefore, unmatchedBefore, true);
        } else {
            fuzzyTrace = new FuzzyTrace(0, 0, true);
        }
        if (debug) System.err.printf("  [fuzzy] recovered %d of unmatched rows%n", fuzzyResult.pairs().size());

        DirectionTrace directionTrace = new DirectionTrace(discoveryTrace, learningTrace, applicationTrace, fuzzyTrace);

        return new DirectionResult(
                JoinResult.of(allPairs, learned.program.describe()),
                learned.score,
                distinctSourceColumns(learned.program),
                directionTrace,
                discoveryMs, learningMs, joinMs, fuzzyMs);
    }



    private static FuzzyRecoveryResult fuzzyRecover(LearnedTransformation learned,
                                                    Table sourceTable, Table targetTable) {
        Optional<Column> tgtColOpt = targetTable.getColumn(learned.targetColumnName);
        if (tgtColOpt.isEmpty()) return new FuzzyRecoveryResult(List.of(), List.of(), 0.0, 0);
        List<String> targetVals = tgtColOpt.get().getValues();

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

        boolean[] srcMatched = new boolean[n];
        int unmatchedCount = 0;
        for (int i = 0; i < n; i++) {
            String d = derived.get(i);
            srcMatched[i] = d != null
                    && Integer.valueOf(1).equals(targetValueCounts.get(d));
            if (!srcMatched[i]) unmatchedCount++;
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
        if (unmatchedCount == 0 || !anyUnmatchedTgt) {
            return new FuzzyRecoveryResult(List.of(), List.of(), 0.0, unmatchedCount);
        }

        RecoveryOutcome outcome = new ConstrainedFuzzyJoin()
                .recoverUnmatched(derived, targetVals, srcMatched, tgtMatched);

        List<Row[]> pairs = new ArrayList<>(outcome.getResults().size());
        for (FuzzyJoinResult r : outcome.getResults()) {
            pairs.add(new Row[]{
                    sourceTable.getRow(r.sourceRowIndex),
                    targetTable.getRow(r.targetRowIndex)});
        }
        return new FuzzyRecoveryResult(pairs, outcome.getResults(), outcome.getOptimalThreshold(), unmatchedCount);
    }

    private static record FuzzyRecoveryResult(List<Row[]> pairs, List<FuzzyJoinResult> rawResults,
                                               double threshold, int unmatchedBeforeFuzzy) {}


    private static DirectionResult fuzzyOnlyDirection(List<ColumnPairMatches> matches,
                                                      Table sourceTable, Table targetTable) {

        ColumnPairMatches best = null;
        double bestAvg = -1;
        int bestCount = -1;
        for (ColumnPairMatches g : matches) {
            if (g.getMatches().isEmpty()) continue;
            double avg = 0;
            for (MatchResult mr : g.getMatches()) avg += mr.getScore();
            avg /= g.getMatches().size();
            int count = g.getMatches().size();
            if (avg > bestAvg || (avg == bestAvg && count > bestCount)) {
                bestAvg = avg;
                bestCount = count;
                best = g;
            }
        }
        if (best == null) return DirectionResult.empty();

        Optional<Column> srcColOpt = sourceTable.getColumn(best.getSourceColumnName());
        Optional<Column> tgtColOpt = targetTable.getColumn(best.getTargetColumnName());
        if (srcColOpt.isEmpty() || tgtColOpt.isEmpty()) return DirectionResult.empty();

        List<String> srcVals = srcColOpt.get().getValues();
        List<String> tgtVals = tgtColOpt.get().getValues();
        boolean[] srcMatched = new boolean[srcVals.size()];
        boolean[] tgtMatched = new boolean[tgtVals.size()];

        RecoveryOutcome outcome = new ConstrainedFuzzyJoin()
                .recoverUnmatched(srcVals, tgtVals, srcMatched, tgtMatched);
        if (outcome.getResults().isEmpty()) return DirectionResult.empty();

        List<Row[]> pairs = new ArrayList<>(outcome.getResults().size());
        for (FuzzyJoinResult r : outcome.getResults()) {
            pairs.add(new Row[]{
                    sourceTable.getRow(r.sourceRowIndex),
                    targetTable.getRow(r.targetRowIndex)});
        }
        String desc = "Fuzzy join (no transform): " + best.getSourceColumnName() + " ~ " + best.getTargetColumnName();

        return new DirectionResult(JoinResult.of(pairs, desc), -srcVals.size(), 1, DirectionTrace.empty(),
                0, 0, 0, 0);
    }
}
