package com.autojoin.synthesis;

import com.autojoin.model.Column;
import com.autojoin.model.Row;
import com.autojoin.model.Table;
import com.autojoin.operator.ConstantOp;
import com.autojoin.operator.LogicalOperator;
import com.autojoin.q_gram.ColumnPairMatches;
import com.autojoin.q_gram.MatchResult;

import java.util.*;

/**
 * Implements Algorithm 4 (LearnTransform) from the paper.
 *
 * For each (sourceColumn, targetKeyColumn) pair produced by the q-gram
 * discovery phase, this class:
 *   1. Takes the top-k joinable row pairs (by q-gram score).
 *   2. Generates r random subsets of size b from those pairs.
 *   3. Calls TryLearnTransform on each subset via TransformationSynthesizer.
 *   4. Applies every successful transformation to the full source table and
 *      scores it by how many derived keys form a clean 1:1 join — i.e. a key
 *      produced by exactly one source row that matches exactly one target row.
 *      Collisions on either side (many source rows → one key, or one key → many
 *      target rows) are excluded, so lossy projections and degenerate constants
 *      score poorly.
 *   5. Rejects constant-only programs and returns the LearnedTransformation with
 *      the highest injective score (null if none scores at least 1).
 *
 */
public class TransformationLearner {

    private static final int TOP_K = 20;
    private static final int SUBSET_SIZE = 3;
    private static final int SUBSETS_PER_GROUP = 12;
    private static final int MAX_TOTAL_SUBSETS = 512;
    private static final int MIN_DERIVED_KEY_LEN = 3;

    private static final int WAVE_SIZE = 4;

    static final boolean DEBUG = Boolean.getBoolean("autojoin.debug");


    public static final class LearnedTransformation {
        public final TransformationProgram program;
        public final String sourceColumnName;
        public final String targetColumnName;
        public final int score;

        LearnedTransformation(TransformationProgram program,
                               String sourceColumnName,
                               String targetColumnName,
                               int score) {
            this.program = program;
            this.sourceColumnName = sourceColumnName;
            this.targetColumnName = targetColumnName;
            this.score = score;
        }
    }

    /**
     * Try to learn a transformation from sourceTable to targetTable's key columns.
     *
     * @param columnPairMatches q-gram matches grouped by (sourceCol, targetCol)
     * @param sourceTable       the source table whose rows are the transformation input
     * @param targetTable       the target table — we match against its key columns
     * @return the best LearnedTransformation found, or null if none succeeded
     */
    public LearnedTransformation learn(List<ColumnPairMatches> columnPairMatches,
                                       Table sourceTable,
                                       Table targetTable) {
        List<ColumnPairMatches> orderedGroups = new ArrayList<>(columnPairMatches);
        orderedGroups.sort((a, b) -> {
            int byScore = Double.compare(avgScore(b), avgScore(a));
            if (byScore != 0) return byScore;
            return Integer.compare(b.getMatches().size(), a.getMatches().size());
        });

        int maxMatches = 0;
        for (ColumnPairMatches g : orderedGroups) {
            maxMatches = Math.max(maxMatches, g.getMatches().size());
        }

        List<ColumnPairMatches> tasks = new ArrayList<>();
        List<Integer> budgets = new ArrayList<>();
        int remainingBudget = MAX_TOTAL_SUBSETS;
        for (ColumnPairMatches group : orderedGroups) {
            if (remainingBudget <= 0) break; // Algorithm 4's L cap

            if (group.getMatches().size() * 10 < maxMatches) {
                if (DEBUG) System.err.printf(
                        "  [learn] pruning group %s->%s (%d matches vs max %d)%n",
                        group.getSourceColumnName(), group.getTargetColumnName(),
                        group.getMatches().size(), maxMatches);
                continue;
            }
            if (group.getMatches().isEmpty()) continue;

            int groupBudget = Math.min(SUBSETS_PER_GROUP, remainingBudget);
            remainingBudget -= groupBudget;
            tasks.add(group);
            budgets.add(groupBudget);
        }

        List<LearnedTransformation> groupResults =
                java.util.stream.IntStream.range(0, tasks.size())
                        .parallel()
                        .mapToObj(i -> learnGroup(tasks.get(i), budgets.get(i),
                                sourceTable, targetTable))
                        .collect(java.util.stream.Collectors.toList());

        LearnedTransformation best = null;
        for (LearnedTransformation candidate : groupResults) {
            if (candidate == null) continue;
            if (best == null || candidate.score > best.score) {
                best = candidate;
                if (DEBUG) System.err.printf(
                        "    [learn] NEW BEST score=%d  %s%n",
                        candidate.score, candidate.program.describe());
            }
        }
        return best;
    }

    private LearnedTransformation learnGroup(ColumnPairMatches group,
                                             int groupBudget,
                                             Table sourceTable,
                                             Table targetTable) {
        String srcColName = group.getSourceColumnName();
        String tgtColName = group.getTargetColumnName();

        Optional<Column> tgtColOpt = targetTable.getColumn(tgtColName);
        if (tgtColOpt.isEmpty()) return null;
        Column tgtCol = tgtColOpt.get();

        List<MatchResult> matches = group.getMatches();

        List<MatchResult> topK = matches.subList(0, Math.min(TOP_K, matches.size()));

        List<int[]> pairs = buildPairs(topK);
        if (pairs.isEmpty()) return null;

        List<List<int[]>> subsets = randomSubsets(pairs, SUBSET_SIZE, groupBudget);

        Map<String, Integer> targetValueCounts = new HashMap<>();
        for (String v : tgtCol.getValues()) {
            targetValueCounts.merge(v, 1, Integer::sum);
        }

        long groupStart = System.nanoTime();
        int attempts = 0, slowAttempts = 0;
        LearnedTransformation best = null;

        int waveSize = WAVE_SIZE;
        for (int w = 0; w < subsets.size(); w += waveSize) {
            List<List<int[]>> wave = subsets.subList(w, Math.min(w + waveSize, subsets.size()));
            List<AttemptResult> results = wave.parallelStream()
                    .map(subset -> runAttempt(subset, sourceTable, tgtCol))
                    .collect(java.util.stream.Collectors.toList());

            for (AttemptResult r : results) {
                if (r == null) continue; // subset had no usable examples

                attempts++;
                if (r.millis > 200) {
                    slowAttempts++;
                    if (DEBUG) System.err.printf(
                            "    [learn] SLOW attempt %dms  %s->%s  nodes=%d  examples=%s%n",
                            r.millis, srcColName, tgtColName, r.nodes,
                            describeExamples(r.examples));
                }

                if (r.program == null) continue;

                if (isConstantOnly(r.program)) continue;

                int score = computeScore(r.program, sourceTable, targetValueCounts);
                if (DEBUG) System.err.printf(
                        "    [learn] candidate %s->%s score=%d  %s%n",
                        srcColName, tgtColName, score, r.program.describe());
                if (score < 1) continue; // no clean 1:1 match — not a real join
                if (best == null || score > best.score) {
                    best = new LearnedTransformation(r.program, srcColName, tgtColName, score);
                }
            }
        }

        if (DEBUG) {
            long gms = (System.nanoTime() - groupStart) / 1_000_000;
            System.err.printf(
                    "  [learn] group %s->%s  matches=%d topK=%d pairs=%d  attempts=%d (slow=%d)  %dms%n",
                    srcColName, tgtColName, matches.size(), topK.size(), pairs.size(),
                    attempts, slowAttempts, gms);
        }
        return best;
    }

    private static final class AttemptResult {
        final TransformationProgram program; // null if none found
        final long millis;
        final int nodes;
        final List<ExamplePair> examples;

        AttemptResult(TransformationProgram program,
                      long millis, int nodes, List<ExamplePair> examples) {
            this.program = program;
            this.millis = millis;
            this.nodes = nodes;
            this.examples = examples;
        }
    }

    private static AttemptResult runAttempt(List<int[]> subset,
                                            Table sourceTable,
                                            Column tgtCol) {
        List<ExamplePair> examples = toExamplePairs(subset, sourceTable, tgtCol);
        if (examples.isEmpty()) return null;

        TransformationSynthesizer synthesizer = new TransformationSynthesizer();
        long t0 = System.nanoTime();
        TransformationProgram program = synthesizer.tryLearnTransform(examples);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        return new AttemptResult(program, ms, synthesizer.lastNodesVisited(), examples);
    }

    private static String describeExamples(List<ExamplePair> examples) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < examples.size(); i++) {
            if (i > 0) sb.append("; ");
            ExamplePair ex = examples.get(i);
            sb.append(String.join(",", ex.sourceRow)).append(" => ").append(ex.targetValue);
        }
        return sb.append(']').toString();
    }

    static int computeScore(TransformationProgram program,
                            Table sourceTable,
                            Map<String, Integer> targetValueCounts) {
        Map<String, Integer> derivedCounts = new HashMap<>();
        for (int i = 0; i < sourceTable.numRows(); i++) {
            String[] row = rowToArray(sourceTable.getRow(i));
            String derived = program.apply(row);
            if (derived != null) {
                derivedCounts.merge(derived, 1, Integer::sum);
            }
        }

        int score = 0;
        for (Map.Entry<String, Integer> e : derivedCounts.entrySet()) {
            if (e.getValue() != 1) continue;
            if (e.getKey().length() < MIN_DERIVED_KEY_LEN) continue;
            Integer tgtCount = targetValueCounts.get(e.getKey());
            if (tgtCount != null && tgtCount == 1) score++;
        }
        return score;
    }

    private static boolean isConstantOnly(TransformationProgram program) {
        List<LogicalOperator> ops = program.getOperators();
        if (ops.isEmpty()) return true;
        for (LogicalOperator op : ops) {
            if (!(op instanceof ConstantOp)) return false;
        }
        return true;
    }

    public static List<Row[]> applyJoin(TransformationProgram program,
                                         Table sourceTable,
                                         Table targetTable,
                                         String targetKeyColumnName) {
        Optional<Column> tgtColOpt = targetTable.getColumn(targetKeyColumnName);
        if (tgtColOpt.isEmpty()) return List.of();
        Column tgtCol = tgtColOpt.get();

        Map<String, Integer> targetValueCounts = new HashMap<>();
        for (String v : tgtCol.getValues()) {
            targetValueCounts.merge(v, 1, Integer::sum);
        }
        Map<String, Integer> targetIndex = new HashMap<>();
        for (int i = 0; i < tgtCol.getValues().size(); i++) {
            targetIndex.putIfAbsent(tgtCol.getValue(i), i);
        }

        List<Row[]> results = new ArrayList<>();
        for (int i = 0; i < sourceTable.numRows(); i++) {
            String[] rowArr = rowToArray(sourceTable.getRow(i));
            String derived = program.apply(rowArr);
            if (derived == null) continue;
            Integer tCount = targetValueCounts.get(derived);
            if (tCount == null || tCount != 1) continue;
            Integer tgtIdx = targetIndex.get(derived);
            if (tgtIdx != null) {
                results.add(new Row[]{sourceTable.getRow(i), targetTable.getRow(tgtIdx)});
            }
        }
        return results;
    }


    private static double avgScore(ColumnPairMatches group) {
        List<MatchResult> matches = group.getMatches();
        if (matches.isEmpty()) return 0.0;
        double sum = 0;
        for (MatchResult m : matches) sum += m.getScore();
        return sum / matches.size();
    }

    private static List<int[]> buildPairs(List<MatchResult> matches) {
        List<int[]> pairs = new ArrayList<>();
        for (MatchResult m : matches) {
            List<Integer> srcRows = m.getBestSourceRows();
            List<Integer> tgtRows = m.getBestTargetRows();
            if (srcRows.isEmpty() || tgtRows.isEmpty()) continue;
            pairs.add(new int[]{srcRows.get(0), tgtRows.get(0)});
        }
        return pairs;
    }

    private static List<List<int[]>> randomSubsets(List<int[]> pairs, int size, int count) {
        if (pairs.size() <= size) {
            return List.of(new ArrayList<>(pairs));
        }
        Random rng = new Random(42);
        List<List<int[]>> subsets = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        int attempts = 0;
        while (subsets.size() < count && attempts < count * 10) {
            attempts++;
            List<int[]> copy = new ArrayList<>(pairs);
            Collections.shuffle(copy, rng);
            List<int[]> subset = copy.subList(0, size);
            String key = subsetKey(subset);
            if (seen.add(key)) {
                subsets.add(new ArrayList<>(subset));
            }
        }
        return subsets;
    }

    private static String subsetKey(List<int[]> subset) {
        List<String> parts = new ArrayList<>(subset.size());
        for (int[] p : subset) parts.add(p[0] + ":" + p[1]);
        Collections.sort(parts);
        return String.join(",", parts);
    }

    private static List<ExamplePair> toExamplePairs(List<int[]> pairs,
                                                      Table sourceTable,
                                                      Column targetKeyColumn) {
        List<ExamplePair> examples = new ArrayList<>();
        for (int[] p : pairs) {
            int srcIdx = p[0], tgtIdx = p[1];
            if (srcIdx >= sourceTable.numRows() || tgtIdx >= targetKeyColumn.size()) continue;
            String[] srcRow = rowToArray(sourceTable.getRow(srcIdx));
            String tgtValue = targetKeyColumn.getValue(tgtIdx);
            if (tgtValue == null || tgtValue.isBlank()) continue;
            examples.add(new ExamplePair(srcRow, tgtValue));
        }
        return examples;
    }

    static String[] rowToArray(Row row) {
        return row.getValues().toArray(new String[0]);
    }
}
