package com.autojoin.synthesis;

import com.autojoin.operator.LogicalOperator;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Implements Algorithm 5 (TryLearnTransform) from the paper.
 *
 * Given a set of (sourceRow, targetValue) example pairs, the synthesiser
 * searches for a minimum-complexity TransformationProgram whose concatenated
 * output equals targetValue on every example.
 *
 * The algorithm is recursive:
 *   1. Find the highest-scoring candidate operator theta whose output appears
 *      as a substring of every example's target.
 *   2. Split the remaining target around theta's output into a left/right part.
 *   3. Recurse on the left parts, then the right parts.
 *   4. If both recursive calls succeed, return left_ops + [theta] + right_ops.
 *   5. Otherwise backtrack and try the next-best operator.
 *
 */
public class TransformationSynthesizer {

    private static final int MAX_OPERATORS = 10;

    private static final int MAX_NODES = 200_000;

    private static final long BUDGET_NANOS =
            Long.getLong("autojoin.synthBudgetMs", 800L) * 1_000_000L;

    private static final java.lang.management.ThreadMXBean THREAD_MX =
            java.lang.management.ManagementFactory.getThreadMXBean();

    private static long threadCpuNanos() {
        return THREAD_MX.isCurrentThreadCpuTimeSupported()
                ? THREAD_MX.getCurrentThreadCpuTime()
                : System.nanoTime();
    }

    private Map<String, TransformationProgram> solved;
    private Set<String> failed;
    private int nodesVisited;
    private long deadlineNanos;

    private static final class SearchAbortedException extends RuntimeException {
        SearchAbortedException() { super(null, null, false, false); }
    }

    int lastNodesVisited() { return nodesVisited; }

    /**
     * Attempt to learn a transformation from the given examples.
     *
     * @return the learned program, or null if no consistent program was found.
     */
    public TransformationProgram tryLearnTransform(List<ExamplePair> examples) {
        solved = new HashMap<>();
        failed = new HashSet<>();
        nodesVisited = 0;
        deadlineNanos = threadCpuNanos() + BUDGET_NANOS;
        try {
            return search(examples, MAX_OPERATORS);
        } catch (SearchAbortedException aborted) {
            return null;
        }
    }


    /**
     * @param budget the maximum number of operators the returned program may
     *               use (the remaining tau budget for this sub-search).
     */
    private TransformationProgram search(List<ExamplePair> examples, int budget) {
        if (++nodesVisited > MAX_NODES || threadCpuNanos() > deadlineNanos) {
            throw new SearchAbortedException();
        }

        if (examples.stream().allMatch(ex -> ex.targetValue.isEmpty())) {
            return new TransformationProgram(List.of());
        }

        boolean anyEmpty = examples.stream().anyMatch(ex -> ex.targetValue.isEmpty());
        if (anyEmpty) return null;

        if (budget <= 0) return null;

        if (anyExampleCharDisjoint(examples) && !allTargetsEqual(examples)) {
            return null;
        }

        String state = stateKey(examples) + "#" + budget;
        TransformationProgram cached = solved.get(state);
        if (cached != null) return cached;
        if (failed.contains(state)) return null;

        TransformationProgram result = searchUncached(examples, budget);

        if (result != null) {
            solved.put(state, result);
        } else {
            failed.add(state);
        }
        return result;
    }

    private TransformationProgram searchUncached(List<ExamplePair> examples, int budget) {
        List<LogicalOperator> candidates = CandidateGenerator.generate(examples);

        for (LogicalOperator op : candidates) {
            List<OperatorPlacement> placements = findPlacements(op, examples);
            if (placements == null) continue;

            int childBudget = budget - 1;

            List<ExamplePair> leftExamples = buildRemainders(examples, placements, Side.LEFT);
            List<ExamplePair> rightExamples = buildRemainders(examples, placements, Side.RIGHT);

            int minOpsNeeded = 1
                    + (anyNonEmptyTarget(leftExamples) ? 1 : 0)
                    + (anyNonEmptyTarget(rightExamples) ? 1 : 0);
            if (minOpsNeeded > budget) continue;

            TransformationProgram leftProg = search(leftExamples, childBudget);
            if (leftProg == null) continue;

            TransformationProgram rightProg = search(rightExamples, childBudget - leftProg.getOperators().size());
            if (rightProg == null) continue;

            List<LogicalOperator> allOps = new ArrayList<>(leftProg.getOperators());
            allOps.add(op);
            allOps.addAll(rightProg.getOperators());
            return new TransformationProgram(allOps);
        }

        return null;
    }


    private static boolean anyExampleCharDisjoint(List<ExamplePair> examples) {
        for (ExamplePair ex : examples) {
            Set<Character> sourceChars = new HashSet<>();
            for (String cell : ex.sourceRow) {
                if (cell == null) continue;
                for (int i = 0; i < cell.length(); i++) {
                    sourceChars.add(cell.charAt(i));
                }
            }
            boolean disjoint = true;
            for (int i = 0; i < ex.targetValue.length(); i++) {
                if (sourceChars.contains(ex.targetValue.charAt(i))) {
                    disjoint = false;
                    break;
                }
            }
            if (disjoint) return true;
        }
        return false;
    }

    private static boolean anyNonEmptyTarget(List<ExamplePair> examples) {
        for (ExamplePair ex : examples) {
            if (!ex.targetValue.isEmpty()) return true;
        }
        return false;
    }

    private static boolean allTargetsEqual(List<ExamplePair> examples) {
        String first = examples.get(0).targetValue;
        for (ExamplePair ex : examples) {
            if (!ex.targetValue.equals(first)) return false;
        }
        return true;
    }


    private static List<OperatorPlacement> findPlacements(LogicalOperator op,
                                                           List<ExamplePair> examples) {
        List<OperatorPlacement> placements = new ArrayList<>(examples.size());
        for (ExamplePair ex : examples) {
            String result;
            try {
                result = op.apply(ex.sourceRow);
            } catch (Exception e) {
                return null;
            }
            if (result == null || result.isEmpty()) return null;

            int pos = ex.targetValue.indexOf(result);
            if (pos < 0) return null;

            placements.add(new OperatorPlacement(result, pos));
        }
        return placements;
    }

    private enum Side { LEFT, RIGHT }

    private static List<ExamplePair> buildRemainders(List<ExamplePair> examples,
                                                      List<OperatorPlacement> placements,
                                                      Side side) {
        List<ExamplePair> result = new ArrayList<>(examples.size());
        for (int i = 0; i < examples.size(); i++) {
            ExamplePair ex = examples.get(i);
            OperatorPlacement p = placements.get(i);
            String remainder = side == Side.LEFT
                    ? ex.targetValue.substring(0, p.position)
                    : ex.targetValue.substring(p.position + p.result.length());
            result.add(new ExamplePair(ex.sourceRow, remainder));
        }
        return result;
    }

    private static String stateKey(List<ExamplePair> examples) {
        StringBuilder sb = new StringBuilder();
        for (ExamplePair ex : examples) {
            sb.append(ex.targetValue.length()).append(':').append(ex.targetValue);
        }
        return sb.toString();
    }


    private static final class OperatorPlacement {
        final String result;
        final int position;

        OperatorPlacement(String result, int position) {
            this.result = result;
            this.position = position;
        }
    }
}