package com.autojoin;

import com.autojoin.model.Row;
import com.autojoin.model.Table;
import com.autojoin.q_gram.AutoJoinDiscovery;
import com.autojoin.q_gram.ColumnPairMatches;
import com.autojoin.synthesis.TransformationLearner;
import com.autojoin.synthesis.TransformationLearner.LearnedTransformation;
import com.autojoin.trace.AlgorithmTrace;
import com.autojoin.trace.ApplicationTrace;
import com.autojoin.trace.DirectionTrace;
import com.autojoin.trace.DiscoveryTrace;
import com.autojoin.trace.LearningTrace;

import java.util.ArrayList;
import java.util.List;

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

        DiscoveryTrace discoveryTrace = new DiscoveryTrace();

        if (matches.isEmpty()) return DirectionResult.empty();

        long t1 = System.nanoTime();
        LearnedTransformation learned = learner.learn(matches, sourceTable, targetTable);
        if (debug) System.err.printf("  [learn] total %dms%n", (System.nanoTime() - t1) / 1_000_000);

        LearningTrace learningTrace = new LearningTrace();

        if (learned == null) return DirectionResult.empty();

        List<Row[]> joinedPairs = TransformationLearner.applyJoin(
                learned.program, sourceTable, targetTable, learned.targetColumnName);

        ApplicationTrace applicationTrace = new ApplicationTrace();

        if (joinedPairs.isEmpty()) return DirectionResult.empty();

        DirectionTrace directionTrace = new DirectionTrace(discoveryTrace, learningTrace, applicationTrace);

        return new DirectionResult(
                JoinResult.of(joinedPairs, learned.program.describe()),
                learned.score,
                directionTrace);
    }
}
