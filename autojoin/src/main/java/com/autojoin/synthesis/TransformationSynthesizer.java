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
 * Pruning (Appendix G, "Optimization for Learning"):
 *  - Best-first candidate order: CandidateGenerator scores every operator by
 *    the progress it makes towards the target and returns them highest-first.
 *  - Shared-character backtrack: if any example's source row shares no
 *    character with its target remainder, that example's whole output must be
 *    produced by row-independent Constant operators; a consistent program can
 *    then exist only if every target remainder is identical.
 *
 * Memorisation: the paper (Section 3.2) frames learning as a search problem
 * ("Like shortest path algorithms, we want to reach the goal state"). Every
 * recursive step removes a non-empty operator output from the target, so each
 * child's remainders are strictly shorter than its parent's -- the search is
 * guaranteed to terminate, and a sub-search is fully determined by its list of
 * target remainders (the source rows are constant within one learning attempt).
 * We cache results on that state. This does not change the algorithm's output;
 * it only avoids re-deriving a subproblem already solved. Without it, an
 * unsatisfiable input (e.g. a transformation whose target contains characters
 * absent from the source) makes the same dead subproblem be re-explored through
 * exponentially many paths -- the paper's leaf-level pruning alone does not
 * prevent that re-derivation.
 */
public class TransformationSynthesizer {

    /** Cache of solved sub-searches: target-remainder state -> learned program. */
    private Map<String, TransformationProgram> solved;
    /** Cache of sub-searches proven to have no consistent program. */
    private Set<String> failed;

    /**
     * Attempt to learn a transformation from the given examples.
     *
     * @return the learned program, or null if no consistent program was found.
     */
    public TransformationProgram tryLearnTransform(List<ExamplePair> examples) {
        // Fresh memo per top-level attempt: the source rows differ between
        // attempts, so cached results must not leak across them.
        solved = new HashMap<>();
        failed = new HashSet<>();
        return search(examples);
    }

    // -------------------------------------------------------------------------
    // Core recursive routine
    // -------------------------------------------------------------------------

    private TransformationProgram search(List<ExamplePair> examples) {
        // Base case: all target remainders are empty -> the empty program is correct.
        if (examples.stream().allMatch(ex -> ex.targetValue.isEmpty())) {
            return new TransformationProgram(List.of());
        }

        // Any empty target remainder with a non-empty one is inconsistent.
        boolean anyEmpty = examples.stream().anyMatch(ex -> ex.targetValue.isEmpty());
        if (anyEmpty) return null;

        // Appendix G: shared-character backtrack. If some example's source row
        // shares no character with its target remainder, no Substr/Split operator
        // can contribute to that example's output, so the whole program must be
        // Constant operators only -- which, being row-independent, produce the
        // same output for every example. If the target remainders are not all
        // identical, no consistent program exists here.
        if (anyExampleCharDisjoint(examples) && !allTargetsEqual(examples)) {
            return null;
        }

        // Memo lookup: the source rows are constant, so the list of target
        // remainders uniquely identifies this sub-search.
        String state = stateKey(examples);
        TransformationProgram cached = solved.get(state);
        if (cached != null) return cached;
        if (failed.contains(state)) return null;

        TransformationProgram result = searchUncached(examples);

        if (result != null) {
            solved.put(state, result);
        } else {
            failed.add(state);
        }
        return result;
    }

    private TransformationProgram searchUncached(List<ExamplePair> examples) {
        List<LogicalOperator> candidates = CandidateGenerator.generate(examples);

        for (LogicalOperator op : candidates) {
            // Find where op(row^i) appears in target^i for every example.
            List<OperatorPlacement> placements = findPlacements(op, examples);
            if (placements == null) continue;

            // Build left-remainder examples (what is to the LEFT of the match).
            List<ExamplePair> leftExamples = buildRemainders(examples, placements, Side.LEFT);
            TransformationProgram leftProg = search(leftExamples);
            if (leftProg == null) continue;

            // Build right-remainder examples (what is to the RIGHT of the match).
            List<ExamplePair> rightExamples = buildRemainders(examples, placements, Side.RIGHT);
            TransformationProgram rightProg = search(rightExamples);
            if (rightProg == null) continue;

            // Compose: left_ops + [op] + right_ops
            List<LogicalOperator> allOps = new ArrayList<>(leftProg.getOperators());
            allOps.add(op);
            allOps.addAll(rightProg.getOperators());
            return new TransformationProgram(allOps);
        }

        return null;
    }

    // -------------------------------------------------------------------------
    // Appendix G pruning helpers
    // -------------------------------------------------------------------------

    /**
     * True if at least one example's source row shares no character with its
     * (non-empty) target remainder.
     */
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

    /** True if every example has the same target remainder. */
    private static boolean allTargetsEqual(List<ExamplePair> examples) {
        String first = examples.get(0).targetValue;
        for (ExamplePair ex : examples) {
            if (!ex.targetValue.equals(first)) return false;
        }
        return true;
    }

    // -------------------------------------------------------------------------
    // Placement helpers
    // -------------------------------------------------------------------------

    /**
     * For each example, compute the string op produces and find where it sits
     * in the target. Returns null if the operator fails or its output is absent
     * from any target.
     */
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

    /** A key that uniquely identifies a sub-search by its ordered target remainders. */
    private static String stateKey(List<ExamplePair> examples) {
        StringBuilder sb = new StringBuilder();
        for (ExamplePair ex : examples) {
            sb.append(ex.targetValue.length()).append(':').append(ex.targetValue);
        }
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // Internal record
    // -------------------------------------------------------------------------

    private static final class OperatorPlacement {
        final String result;
        final int position;

        OperatorPlacement(String result, int position) {
            this.result = result;
            this.position = position;
        }
    }
}