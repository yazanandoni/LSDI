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
 * Paper parameters (Section 3.2 / Algorithm 4):
 *   k = 10   top matches used per group
 *   b = 3    examples per learning attempt
 *   r = 5    random subsets tried per group
 */
public class TransformationLearner {

    private static final int TOP_K = 20;
    private static final int SUBSET_SIZE = 3;
    // Number of random-subset trials per group (Algorithm 4's r / the T of
    // the Appendix H success bound, which uses T = 128). The paper's value is
    // affordable on their hardware; empirically on our benchmarks 128 trials
    // produced identical join results to 12 at ~5x the runtime (the score
    // plateaus almost immediately), so we keep the budget small. Failure
    // probability drops exponentially in T (Proposition 3), so raise this if
    // a dataset's transform is being missed rather than mis-ranked.
    private static final int SUBSETS_PER_GROUP = 12;

    // Algorithm 4's L: global cap on example sets across ALL column-pair
    // groups. Groups are processed in descending order of average q-gram
    // score, so the budget is spent on the most promising column pairs first
    // and a table with many junk column pairs cannot blow up the runtime.
    private static final int MAX_TOTAL_SUBSETS = 512;

    // Stagnation cutoff: stop a group's trials after this many consecutive
    // attempts that fail to improve the group's best score. The exponential
    // success bound (Appendix H) means a better transform that exists is
    // overwhelmingly likely to surface within a window this large, so once
    // the score plateaus the remaining trials of the 128 ceiling would almost
    // surely just re-confirm the incumbent — at full synthesis cost each.
    private static final int STAGNATION_LIMIT = 16;

    /**
     * Minimum length of a derived key for it to count toward the injective score.
     * A transformation that joins on a 1–2 character fragment is almost never a
     * real key — it is the degenerate program the synthesizer falls back to when
     * no genuine transform exists (e.g. a 2-char Substr that forms a handful of
     * accidental 1:1 matches). Such fragments collide heavily and produce
     * spurious joins, so they are not credited and the program is rejected by the
     * score &lt; 1 guard. Real keys in practice (names, years, emails, cities) are
     * comfortably longer than this.
     */
    private static final int MIN_DERIVED_KEY_LEN = 3;

    /**
     * Stop trying subsets of a column pair after this many search-guard aborts.
     * Set to the full trial count — i.e. groups are no longer abandoned on
     * aborts. Junk column pairs are already cut by the relative evidence
     * pruning before any synthesis runs, so the remaining aborts come from
     * REAL join columns whose example pool mixes good pairs with q-gram
     * coincidences or name-style variations (texas govs, us presidents):
     * abandoning those groups after a few poisoned subsets returned an empty
     * join even though clean subsets existed later in the trial sequence.
     */
    private static final int MAX_GROUP_ABORTS = SUBSETS_PER_GROUP;

    /** Attempts synthesized concurrently per wave (see learnGroup). */
    private static final int WAVE_SIZE = 4;

    /** Enable verbose timing on stderr via -Dautojoin.debug=true. */
    static final boolean DEBUG = Boolean.getBoolean("autojoin.debug");

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Result of a successful learning attempt.
     */
    public static final class LearnedTransformation {
        public final TransformationProgram program;
        /** Name of the source column the transformation is applied to (its row index
         *  is the column's position in the source table). */
        public final String sourceColumnName;
        /** Name of the target key column that the transformation output should match. */
        public final String targetColumnName;
        /** Injective-join score: number of derived keys that map exactly one
         *  source row to exactly one target row. */
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
        // Algorithm 4: go through groups in descending order of average q-gram
        // score, so the global subset budget is consumed by the most promising
        // column pairs first. Kept matches are all 1-to-1 (goodness 1.0), so
        // scores usually tie — break ties by match COUNT: a column pair with
        // hundreds of unique q-gram matches (e.g. Title->Title) is far more
        // likely the real join than one with a handful of coincidental hits.
        List<ColumnPairMatches> orderedGroups = new ArrayList<>(columnPairMatches);
        orderedGroups.sort((a, b) -> {
            int byScore = Double.compare(avgScore(b), avgScore(a));
            if (byScore != 0) return byScore;
            return Integer.compare(b.getMatches().size(), a.getMatches().size());
        });

        // Strongest group's evidence, for relative pruning below.
        int maxMatches = 0;
        for (ColumnPairMatches g : orderedGroups) {
            maxMatches = Math.max(maxMatches, g.getMatches().size());
        }

        // Sequential, deterministic prep: prune junk groups and allocate the
        // global subset budget (Algorithm 4's L) in priority order. The actual
        // learning per group then runs in PARALLEL — groups are independent
        // (each gets its own synthesizer; tables and match groups are only
        // read) — and results are reduced in the same priority order, so the
        // outcome is identical to the sequential run, including tie-breaks.
        List<ColumnPairMatches> tasks = new ArrayList<>();
        List<Integer> budgets = new ArrayList<>();
        int remainingBudget = MAX_TOTAL_SUBSETS;
        for (ColumnPairMatches group : orderedGroups) {
            if (remainingBudget <= 0) break; // Algorithm 4's L cap

            // Evidence pruning: a group with under 10% of the strongest
            // group's 1:1 q-gram matches is almost certainly a coincidental
            // column pairing (e.g. Title->Lead vocal(s) with 4 matches vs
            // Title->Title with 271). Synthesis on its garbage examples
            // reliably runs the search guard to exhaustion (~1s per attempt),
            // so these groups dominate runtime while contributing nothing.
            // The threshold is relative, so small tables — where every group
            // has only a handful of matches — are unaffected.
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

        // parallelStream + collect preserves encounter order, so the reduce
        // below sees group results in priority order regardless of which
        // thread finished first.
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

    /**
     * Run the subset-sampling/synthesis loop for one column-pair group and
     * return the group's best transformation (or null). Thread-safe: uses a
     * private synthesizer instance and only reads shared inputs.
     */
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

        // Top-k matches
        List<MatchResult> topK = matches.subList(0, Math.min(TOP_K, matches.size()));

        // Build the pool of (sourceRowIdx, targetRowIdx) pairs
        List<int[]> pairs = buildPairs(topK);
        if (pairs.isEmpty()) return null;

        // Generate random subsets of size b within this group's budget.
        List<List<int[]>> subsets = randomSubsets(pairs, SUBSET_SIZE, groupBudget);

        // Target-side key frequencies are fixed per group; compute them once
        // instead of once per scored candidate program.
        Map<String, Integer> targetValueCounts = new HashMap<>();
        for (String v : tgtCol.getValues()) {
            targetValueCounts.merge(v, 1, Integer::sum);
        }

        long groupStart = System.nanoTime();
        int attempts = 0, slowAttempts = 0, aborts = 0;
        int sinceImprovement = 0;
        boolean abandoned = false;
        LearnedTransformation best = null;

        // Attempts run in parallel WAVES: synthesis calls within a wave are
        // independent (one synthesizer each — the memo is per-attempt anyway),
        // while the early-stop decisions (abort guard, stagnation) are
        // evaluated sequentially between waves in deterministic order. Worst
        // case this wastes one wave of doomed attempts; in return a wave's
        // wall time is its slowest attempt instead of the sum.
        int waveSize = WAVE_SIZE;
        for (int w = 0; w < subsets.size() && !abandoned; w += waveSize) {
            if (sinceImprovement >= STAGNATION_LIMIT) {
                if (DEBUG) System.err.printf(
                        "    [learn] %s->%s plateaued after %d attempts (best=%d)%n",
                        srcColName, tgtColName, attempts, best == null ? 0 : best.score);
                break;
            }

            List<List<int[]>> wave = subsets.subList(w, Math.min(w + waveSize, subsets.size()));
            List<AttemptResult> results = wave.parallelStream()
                    .map(subset -> runAttempt(subset, sourceTable, tgtCol))
                    .collect(java.util.stream.Collectors.toList());

            for (AttemptResult r : results) {
                if (r == null) continue; // subset had no usable examples

                attempts++;
                sinceImprovement++;
                if (r.millis > 200) {
                    slowAttempts++;
                    if (DEBUG) System.err.printf(
                            "    [learn] SLOW attempt %dms  %s->%s  nodes=%d  examples=%s%n",
                            r.millis, srcColName, tgtColName, r.nodes,
                            describeExamples(r.examples));
                }

                // Doomed-pair early stop: if multiple subsets exhaust the search
                // guard without yielding a program, the column pair almost
                // certainly does not join — stop wasting attempts on it. Each
                // guard-aborted attempt costs nearly a second on wide text
                // columns, so cutting these dominates the learning runtime.
                if (r.aborted && ++aborts >= MAX_GROUP_ABORTS) {
                    if (DEBUG) System.err.printf(
                            "    [learn] abandoning %s->%s after %d aborts%n",
                            srcColName, tgtColName, aborts);
                    abandoned = true;
                    break;
                }

                if (r.program == null) continue;

                // Reject degenerate constant-only programs: they ignore the
                // source row and collapse every row onto one fixed key.
                if (isConstantOnly(r.program)) continue;

                int score = computeScore(r.program, sourceTable, targetValueCounts);
                if (DEBUG) System.err.printf(
                        "    [learn] candidate %s->%s score=%d  %s%n",
                        srcColName, tgtColName, score, r.program.describe());
                if (score < 1) continue; // no clean 1:1 match — not a real join
                if (best == null || score > best.score) {
                    best = new LearnedTransformation(r.program, srcColName, tgtColName, score);
                    sinceImprovement = 0;
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

    /** Outcome of one synthesis attempt, carried back from a worker thread. */
    private static final class AttemptResult {
        final TransformationProgram program; // null if none found
        final boolean aborted;
        final long millis;
        final int nodes;
        final List<ExamplePair> examples;

        AttemptResult(TransformationProgram program, boolean aborted,
                      long millis, int nodes, List<ExamplePair> examples) {
            this.program = program;
            this.aborted = aborted;
            this.millis = millis;
            this.nodes = nodes;
            this.examples = examples;
        }
    }

    /**
     * Run one synthesis attempt on its own synthesizer instance (the memo is
     * per-attempt, so nothing is lost vs. a shared one). Safe to call from
     * parallel worker threads. Returns null when the subset has no usable
     * examples.
     */
    private static AttemptResult runAttempt(List<int[]> subset,
                                            Table sourceTable,
                                            Column tgtCol) {
        List<ExamplePair> examples = toExamplePairs(subset, sourceTable, tgtCol);
        if (examples.isEmpty()) return null;

        TransformationSynthesizer synthesizer = new TransformationSynthesizer();
        long t0 = System.nanoTime();
        TransformationProgram program = synthesizer.tryLearnTransform(examples);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        return new AttemptResult(program, synthesizer.lastAttemptAborted(),
                ms, synthesizer.lastNodesVisited(), examples);
    }

    /** Compact one-line view of example pairs, for debug logging. */
    private static String describeExamples(List<ExamplePair> examples) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < examples.size(); i++) {
            if (i > 0) sb.append("; ");
            ExamplePair ex = examples.get(i);
            sb.append(String.join(",", ex.sourceRow)).append(" => ").append(ex.targetValue);
        }
        return sb.append(']').toString();
    }

    // -------------------------------------------------------------------------
    // Injective-join scoring
    // -------------------------------------------------------------------------

    /**
     * Score a transformation by how many derived keys form a clean 1:1 join.
     *
     * A derived key counts only when it is produced by exactly one source row
     * AND matches exactly one target row. This rewards transformations that
     * uniquely identify a join partner and penalizes:
     *   - source-side collisions (many source rows → the same key), which is how
     *     numeric-column joins and constants degenerate, and
     *   - target-side collisions (one key → many target rows), which is how a
     *     lossy projection of a composite key (e.g. dropping "Type") degenerates.
     */
    static int computeScore(TransformationProgram program,
                            Table sourceTable,
                            Map<String, Integer> targetValueCounts) {
        // How many source rows produce each derived value.
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
            if (e.getValue() != 1) continue;                       // many source rows → ambiguous
            if (e.getKey().length() < MIN_DERIVED_KEY_LEN) continue; // degenerate short fragment
            Integer tgtCount = targetValueCounts.get(e.getKey());
            if (tgtCount != null && tgtCount == 1) score++;        // matches exactly one target row
        }
        return score;
    }

    /** True if every operator in the program is a row-independent Constant. */
    private static boolean isConstantOnly(TransformationProgram program) {
        List<LogicalOperator> ops = program.getOperators();
        if (ops.isEmpty()) return true;
        for (LogicalOperator op : ops) {
            if (!(op instanceof ConstantOp)) return false;
        }
        return true;
    }

    /**
     * Produce the equi-join result: for each source row, apply the program and
     * match against the target key column. Returns matched (sourceRow, targetRow)
     * pairs.
     *
     * Join cardinality follows the paper's Definition 1: the target column is
     * a KEY column, so joins are 1:1 or N:1. Target-side uniqueness is
     * enforced (a derived key matching several target rows is ambiguous — we
     * cannot tell which row is right, so we omit it rather than guess), but
     * MANY source rows may join the same target row: real tables repeat
     * entities across rows (e.g. one row per presidential TERM, with
     * multi-term presidents appearing twice), and the per-row foreign key is
     * still unambiguous. The transform itself is still SELECTED by the strict
     * injective score of {@link #computeScore}, which keeps lossy projections
     * (that collapse distinct entities onto one key) from winning.
     */
    public static List<Row[]> applyJoin(TransformationProgram program,
                                         Table sourceTable,
                                         Table targetTable,
                                         String targetKeyColumnName) {
        Optional<Column> tgtColOpt = targetTable.getColumn(targetKeyColumnName);
        if (tgtColOpt.isEmpty()) return List.of();
        Column tgtCol = tgtColOpt.get();

        // How many target rows hold each target key value (for target-side uniqueness).
        Map<String, Integer> targetValueCounts = new HashMap<>();
        for (String v : tgtCol.getValues()) {
            targetValueCounts.merge(v, 1, Integer::sum);
        }
        // First target row index for each key value.
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
            if (tCount == null || tCount != 1) continue;       // target-side ambiguous / no match
            Integer tgtIdx = targetIndex.get(derived);
            if (tgtIdx != null) {
                results.add(new Row[]{sourceTable.getRow(i), targetTable.getRow(tgtIdx)});
            }
        }
        return results;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Average q-gram goodness of a group's matches (Algorithm 4 group order). */
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
            // Use the first source row and first target row from each match result
            pairs.add(new int[]{srcRows.get(0), tgtRows.get(0)});
        }
        return pairs;
    }

    private static List<List<int[]>> randomSubsets(List<int[]> pairs, int size, int count) {
        if (pairs.size() <= size) {
            return List.of(new ArrayList<>(pairs));
        }
        Random rng = new Random(42); // deterministic for reproducibility
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
        // Order-independent key: the same 3 pairs drawn in a different shuffle
        // order are the same example set — each duplicate would otherwise cost
        // a full (identical) synthesis attempt.
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
        // Positional values: preserves every column even when two share a name.
        return row.getValues().toArray(new String[0]);
    }
}
