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

    /**
     * Bounds on candidate enumeration. Without these, generate() is
     * O(seps1·parts1·seps2·parts2·len²·casings) per call and explodes on long,
     * separator-rich source fields (e.g. a 90-char "Songwriter(s)" value with
     * ~30 separators), costing seconds per search node. Real join-key
     * transformations use a handful of separators, low part indices, and short
     * extracted components, so these caps do not affect them.
     */
    private static final int MAX_SEPARATORS = 8;
    private static final int MAX_SPLIT_PARTS = 8;
    private static final int MAX_SUBSTR_LEN = 40;

    // -------------------------------------------------------------------------
    // Public entry point
    // -------------------------------------------------------------------------

    static List<LogicalOperator> generate(List<ExamplePair> examples) {
        if (examples.isEmpty()) return List.of();

        List<ScoredOp> candidates = new ArrayList<>();

        // Appendix G casing pruning: only enumerate casings whose output could
        // appear in the targets. Computed once and shared by all Substr-family
        // generators.
        Set<Casing> casings = usefulCasings(examples);

        addConstantCandidates(examples, candidates);
        addSubstrCandidates(examples, candidates, casings);
        addSplitSubstrCandidates(examples, candidates, casings);
        addSplitSplitSubstrCandidates(examples, candidates, casings);

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
                                             List<ScoredOp> out, Set<Casing> casings) {
        int numCols = examples.get(0).sourceRow.length;

        for (int k = 0; k < numCols; k++) {
            // Get element at column k for each example
            List<String> elems = elementsAt(examples, k);
            if (elems == null) continue;

            for (Casing casing : casings) {
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
            // fixed lengths (capped: real key components are short)
            int maxEnd = Math.min(elem0.length(), start + MAX_SUBSTR_LEN);
            for (int end = start + 1; end <= maxEnd; end++) {
                addIfValid(new SubstrOp(k, start, end - start, casing), examples, out);
            }
        }
    }

    // -------------------------------------------------------------------------
    // SplitSubstr candidates
    // -------------------------------------------------------------------------

    private static void addSplitSubstrCandidates(List<ExamplePair> examples,
                                                   List<ScoredOp> out, Set<Casing> casings) {
        int numCols = examples.get(0).sourceRow.length;

        for (int k = 0; k < numCols; k++) {
            List<String> elems = elementsAt(examples, k);
            if (elems == null) continue;

            Set<String> seps = extractSeparators(elems);
            for (String sep : seps) {
                // Determine max number of parts across all examples after splitting
                int maxParts = Math.min(maxParts(elems, sep), MAX_SPLIT_PARTS);
                if (maxParts < 2) continue;

                for (int m = 0; m < maxParts; m++) {
                    List<String> parts = splitElems(elems, sep, m);
                    if (parts == null) continue;

                    for (Casing casing : casings) {
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
            int maxEnd = Math.min(part0.length(), start + MAX_SUBSTR_LEN);
            for (int end = start + 1; end <= maxEnd; end++) {
                addIfValid(new SplitSubstrOp(k, sep, m, start, end - start, casing), examples, out);
            }
        }
    }

    // -------------------------------------------------------------------------
    // SplitSplitSubstr candidates
    // -------------------------------------------------------------------------

    private static void addSplitSplitSubstrCandidates(List<ExamplePair> examples,
                                                        List<ScoredOp> out, Set<Casing> casings) {
        int numCols = examples.get(0).sourceRow.length;

        for (int k1 = 0; k1 < numCols; k1++) {
            List<String> elems = elementsAt(examples, k1);
            if (elems == null) continue;

            Set<String> seps1 = extractSeparators(elems);
            for (String sep1 : seps1) {
                int maxParts1 = Math.min(maxParts(elems, sep1), MAX_SPLIT_PARTS);
                if (maxParts1 < 2) continue;

                for (int k2 = 0; k2 < maxParts1; k2++) {
                    List<String> afterFirstSplit = splitElems(elems, sep1, k2);
                    if (afterFirstSplit == null) continue;

                    Set<String> seps2 = extractSeparators(afterFirstSplit);
                    for (String sep2 : seps2) {
                        if (sep2.equals(sep1)) continue; // avoid redundancy
                        int maxParts2 = Math.min(maxParts(afterFirstSplit, sep2), MAX_SPLIT_PARTS);
                        if (maxParts2 < 2) continue;

                        for (int m = 0; m < maxParts2; m++) {
                            List<String> finalParts = splitElems(afterFirstSplit, sep2, m);
                            if (finalParts == null) continue;

                            for (Casing casing : casings) {
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
            int maxEnd = Math.min(part0.length(), start + MAX_SUBSTR_LEN);
            for (int end = start + 1; end <= maxEnd; end++) {
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

    /**
     * Appendix G casing pruning: return only the casings that could possibly
     * produce a substring of the example targets, so incompatible casings are
     * never enumerated. NONE (identity) is always kept. A casing that introduces
     * uppercase (UPPER, TITLE) is useless if no target contains an uppercase
     * letter, and LOWER is useless if no target contains a lowercase letter —
     * in those cases the only inputs they could match are letter-free, where the
     * output equals NONE's and is already covered. Pruning is over the union of
     * all example targets, so a casing is dropped only when provably useless for
     * every example (never losing a valid candidate).
     */
    private static Set<Casing> usefulCasings(List<ExamplePair> examples) {
        boolean hasUpper = false, hasLower = false;
        for (ExamplePair ex : examples) {
            String t = ex.targetValue;
            for (int i = 0; i < t.length() && !(hasUpper && hasLower); i++) {
                char c = t.charAt(i);
                if (Character.isUpperCase(c)) hasUpper = true;
                else if (Character.isLowerCase(c)) hasLower = true;
            }
            if (hasUpper && hasLower) break;
        }
        Set<Casing> casings = EnumSet.of(Casing.NONE);
        if (hasLower) casings.add(Casing.LOWER);
        if (hasUpper) { casings.add(Casing.UPPER); casings.add(Casing.TITLE); }
        return casings;
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
        // Single-char separators are the most useful, so collect them first and
        // only fall back to 2-char sequences to fill the budget. This keeps the
        // set small on separator-rich text without losing the common cases.
        Set<String> singles = new LinkedHashSet<>();
        Set<String> doubles = new LinkedHashSet<>();
        for (String elem : elems) {
            for (int i = 0; i < elem.length(); i++) {
                char c = elem.charAt(i);
                if (!Character.isLetterOrDigit(c)) {
                    singles.add(String.valueOf(c));
                    if (i + 1 < elem.length()) doubles.add(elem.substring(i, i + 2));
                    if (i > 0) doubles.add(elem.substring(i - 1, i + 1));
                }
            }
        }

        Set<String> seps = new LinkedHashSet<>();
        for (String s : singles) {
            if (seps.size() >= MAX_SEPARATORS) return seps;
            seps.add(s);
        }
        for (String d : doubles) {
            if (seps.size() >= MAX_SEPARATORS) break;
            seps.add(d);
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
