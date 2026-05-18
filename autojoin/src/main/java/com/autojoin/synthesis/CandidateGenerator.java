package com.autojoin.synthesis;

import com.autojoin.operator.*;

import java.util.*;

/**
 * Enumerates every LogicalOperator instance that produces a non-empty string
 * that appears in the target value for ALL provided examples.
 *
 * Candidates are returned sorted by total coverage score (sum of result
 * lengths across all examples), descending — so the synthesiser tries the
 * most-promising operator first.
 *
 * Pruning follows Appendix G of the paper:
 *  - Separators are restricted to non-alphanumeric substrings actually present
 *    in the source strings.
 *  - Casing variants whose output cannot match the current target are skipped.
 *  - Length=-1 ("to end") is always tried in addition to fixed lengths.
 */
final class CandidateGenerator {

    private CandidateGenerator() {}

    // -------------------------------------------------------------------------
    // Public entry point
    // -------------------------------------------------------------------------

    static List<LogicalOperator> generate(List<ExamplePair> examples) {
        if (examples.isEmpty()) return List.of();

        List<ScoredOp> candidates = new ArrayList<>();

        addConstantCandidates(examples, candidates);
        addSubstrCandidates(examples, candidates);
        addSplitSubstrCandidates(examples, candidates);
        addSplitSplitSubstrCandidates(examples, candidates);

        // Deduplicate by result fingerprint, keeping highest score
        Map<String, ScoredOp> deduped = new LinkedHashMap<>();
        for (ScoredOp s : candidates) {
            String key = fingerprint(s.op, examples);
            deduped.merge(key, s, (a, b) -> a.score >= b.score ? a : b);
        }

        // Sort descending by score
        List<ScoredOp> sorted = new ArrayList<>(deduped.values());
        sorted.sort((a, b) -> Integer.compare(b.score, a.score));

        List<LogicalOperator> result = new ArrayList<>(sorted.size());
        for (ScoredOp s : sorted) result.add(s.op);
        return result;
    }

    // -------------------------------------------------------------------------
    // Constant candidates
    // -------------------------------------------------------------------------

    private static void addConstantCandidates(List<ExamplePair> examples,
                                               List<ScoredOp> out) {
        // Enumerate substrings of the first target that appear in ALL targets.
        // Limit to length 1-5 to avoid combinatorial explosion.
        String target0 = examples.get(0).targetValue;
        int maxLen = Math.min(target0.length(), 5);

        for (int start = 0; start < target0.length(); start++) {
            for (int len = 1; len <= maxLen && start + len <= target0.length(); len++) {
                String constant = target0.substring(start, start + len);
                if (constant.isBlank() && len > 2) continue; // skip long whitespace
                boolean allContain = examples.stream()
                        .allMatch(ex -> ex.targetValue.contains(constant));
                if (allContain) {
                    ConstantOp op = new ConstantOp(constant);
                    int score = examples.size() * constant.length();
                    out.add(new ScoredOp(op, score));
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Substr candidates
    // -------------------------------------------------------------------------

    private static void addSubstrCandidates(List<ExamplePair> examples,
                                             List<ScoredOp> out) {
        int numCols = examples.get(0).sourceRow.length;

        for (int k = 0; k < numCols; k++) {
            // Get element at column k for each example
            List<String> elems = elementsAt(examples, k);
            if (elems == null) continue;

            for (Casing casing : Casing.values()) {
                enumerateSubstrOps(k, elems, casing, examples, out);
            }
        }
    }

    private static void enumerateSubstrOps(int k, List<String> elems, Casing casing,
                                            List<ExamplePair> examples, List<ScoredOp> out) {
        String elem0 = elems.get(0);

        for (int start = 0; start < elem0.length(); start++) {
            // length = -1 (take to end)
            addIfValid(new SubstrOp(k, start, -1, casing), examples, out);
            // fixed lengths
            for (int end = start + 1; end <= elem0.length(); end++) {
                addIfValid(new SubstrOp(k, start, end - start, casing), examples, out);
            }
        }
    }

    // -------------------------------------------------------------------------
    // SplitSubstr candidates
    // -------------------------------------------------------------------------

    private static void addSplitSubstrCandidates(List<ExamplePair> examples,
                                                   List<ScoredOp> out) {
        int numCols = examples.get(0).sourceRow.length;

        for (int k = 0; k < numCols; k++) {
            List<String> elems = elementsAt(examples, k);
            if (elems == null) continue;

            Set<String> seps = extractSeparators(elems);
            for (String sep : seps) {
                // Determine max number of parts across all examples after splitting
                int maxParts = maxParts(elems, sep);
                if (maxParts < 2) continue;

                for (int m = 0; m < maxParts; m++) {
                    List<String> parts = splitElems(elems, sep, m);
                    if (parts == null) continue;

                    for (Casing casing : Casing.values()) {
                        enumerateSplitSubstrOps(k, sep, m, parts, casing, examples, out);
                    }
                }
            }
        }
    }

    private static void enumerateSplitSubstrOps(int k, String sep, int m,
                                                  List<String> parts, Casing casing,
                                                  List<ExamplePair> examples,
                                                  List<ScoredOp> out) {
        String part0 = parts.get(0);
        for (int start = 0; start < part0.length(); start++) {
            addIfValid(new SplitSubstrOp(k, sep, m, start, -1, casing), examples, out);
            for (int end = start + 1; end <= part0.length(); end++) {
                addIfValid(new SplitSubstrOp(k, sep, m, start, end - start, casing), examples, out);
            }
        }
    }

    // -------------------------------------------------------------------------
    // SplitSplitSubstr candidates
    // -------------------------------------------------------------------------

    private static void addSplitSplitSubstrCandidates(List<ExamplePair> examples,
                                                        List<ScoredOp> out) {
        int numCols = examples.get(0).sourceRow.length;

        for (int k1 = 0; k1 < numCols; k1++) {
            List<String> elems = elementsAt(examples, k1);
            if (elems == null) continue;

            Set<String> seps1 = extractSeparators(elems);
            for (String sep1 : seps1) {
                int maxParts1 = maxParts(elems, sep1);
                if (maxParts1 < 2) continue;

                for (int k2 = 0; k2 < maxParts1; k2++) {
                    List<String> afterFirstSplit = splitElems(elems, sep1, k2);
                    if (afterFirstSplit == null) continue;

                    Set<String> seps2 = extractSeparators(afterFirstSplit);
                    for (String sep2 : seps2) {
                        if (sep2.equals(sep1)) continue; // avoid redundancy
                        int maxParts2 = maxParts(afterFirstSplit, sep2);
                        if (maxParts2 < 2) continue;

                        for (int m = 0; m < maxParts2; m++) {
                            List<String> finalParts = splitElems(afterFirstSplit, sep2, m);
                            if (finalParts == null) continue;

                            for (Casing casing : Casing.values()) {
                                enumerateSplitSplitSubstrOps(
                                        k1, sep1, k2, sep2, m, finalParts, casing, examples, out);
                            }
                        }
                    }
                }
            }
        }
    }

    private static void enumerateSplitSplitSubstrOps(int k1, String sep1, int k2,
                                                       String sep2, int m,
                                                       List<String> finalParts,
                                                       Casing casing,
                                                       List<ExamplePair> examples,
                                                       List<ScoredOp> out) {
        String part0 = finalParts.get(0);
        for (int start = 0; start < part0.length(); start++) {
            addIfValid(new SplitSplitSubstrOp(k1, sep1, k2, sep2, m, start, -1, casing),
                    examples, out);
            for (int end = start + 1; end <= part0.length(); end++) {
                addIfValid(new SplitSplitSubstrOp(k1, sep1, k2, sep2, m, start, end - start, casing),
                        examples, out);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Try the operator; if it produces a non-empty result in every example's
     *  target, add it with its total-coverage score. */
    static void addIfValid(LogicalOperator op, List<ExamplePair> examples,
                            List<ScoredOp> out) {
        int total = 0;
        for (ExamplePair ex : examples) {
            String result;
            try {
                result = op.apply(ex.sourceRow);
            } catch (Exception e) {
                return;
            }
            if (result == null || result.isEmpty()) return;
            if (!ex.targetValue.contains(result)) return;
            total += result.length();
        }
        out.add(new ScoredOp(op, total));
    }

    /** A fingerprint that identifies what an operator produces on all examples. */
    private static String fingerprint(LogicalOperator op, List<ExamplePair> examples) {
        StringBuilder sb = new StringBuilder();
        for (ExamplePair ex : examples) {
            try {
                sb.append(op.apply(ex.sourceRow));
            } catch (Exception e) {
                sb.append('\0');
            }
            sb.append('');
        }
        return sb.toString();
    }

    /** Get the value at column k for each example row; null if any row is too short. */
    private static List<String> elementsAt(List<ExamplePair> examples, int k) {
        List<String> result = new ArrayList<>(examples.size());
        for (ExamplePair ex : examples) {
            if (k >= ex.sourceRow.length) return null;
            result.add(ex.sourceRow[k]);
        }
        return result;
    }

    /** Split each elem by sep and return the m-th part; null if any elem lacks that part. */
    private static List<String> splitElems(List<String> elems, String sep, int m) {
        List<String> result = new ArrayList<>(elems.size());
        for (String elem : elems) {
            String[] parts = PhysicalOps.split(elem, sep);
            int idx = m < 0 ? parts.length + m : m;
            if (idx < 0 || idx >= parts.length) return null;
            result.add(parts[idx]);
        }
        return result;
    }

    /** Maximum number of parts any elem has when split by sep. */
    private static int maxParts(List<String> elems, String sep) {
        int max = 0;
        for (String elem : elems) {
            max = Math.max(max, PhysicalOps.split(elem, sep).length);
        }
        return max;
    }

    /**
     * Extract candidate separators: single or double non-alphanumeric character
     * sequences that appear in the source strings (Appendix G).
     */
    static Set<String> extractSeparators(List<String> elems) {
        Set<String> seps = new LinkedHashSet<>();
        for (String elem : elems) {
            for (int i = 0; i < elem.length(); i++) {
                char c = elem.charAt(i);
                if (!Character.isLetterOrDigit(c)) {
                    seps.add(String.valueOf(c));
                    // 2-char sequences containing the non-alphanum char
                    if (i + 1 < elem.length()) {
                        seps.add(elem.substring(i, i + 2));
                    }
                    if (i > 0) {
                        seps.add(elem.substring(i - 1, i + 1));
                    }
                }
            }
        }
        return seps;
    }

    // -------------------------------------------------------------------------
    // Internal record
    // -------------------------------------------------------------------------

    static final class ScoredOp {
        final LogicalOperator op;
        final int score;

        ScoredOp(LogicalOperator op, int score) {
            this.op = op;
            this.score = score;
        }
    }
}
