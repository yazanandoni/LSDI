package com.autojoin.synthesis;

import com.autojoin.operator.*;

import java.util.*;

final class CandidateGenerator {

    private CandidateGenerator() {}

    private static final int MAX_SEPARATORS = 8;
    private static final int MAX_SPLIT_PARTS = 8;
    private static final int MAX_SUBSTR_LEN = 40;

    static List<LogicalOperator> generate(List<ExamplePair> examples) {
        if (examples.isEmpty()) return List.of();

        List<ScoredOp> candidates = new ArrayList<>();

        Set<Casing> casings = usefulCasings(examples);

        addConstantCandidates(examples, candidates);
        addSubstrCandidates(examples, candidates, casings);
        addSplitSubstrCandidates(examples, candidates, casings);
        addSplitSplitSubstrCandidates(examples, candidates, casings);

        Map<String, ScoredOp> deduped = new LinkedHashMap<>();
        for (ScoredOp s : candidates) {
            String key = fingerprint(s.op, examples);
            deduped.merge(key, s, (a, b) -> a.score >= b.score ? a : b);
        }

        List<ScoredOp> sorted = new ArrayList<>(deduped.values());
        sorted.sort((a, b) -> Integer.compare(b.score, a.score));

        List<LogicalOperator> result = new ArrayList<>(sorted.size());
        for (ScoredOp s : sorted) result.add(s.op);
        return result;
    }


    private static void addConstantCandidates(List<ExamplePair> examples,
                                               List<ScoredOp> out) {
        String target0 = examples.get(0).targetValue;
        int maxLen = Math.min(target0.length(), 5);

        for (int start = 0; start < target0.length(); start++) {
            for (int len = 1; len <= maxLen && start + len <= target0.length(); len++) {
                String constant = target0.substring(start, start + len);
                if (constant.isBlank() && len > 2) continue;
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


    private static void addSubstrCandidates(List<ExamplePair> examples,
                                             List<ScoredOp> out, Set<Casing> casings) {
        int numCols = examples.get(0).sourceRow.length;

        for (int k = 0; k < numCols; k++) {
            List<String> elems = elementsAt(examples, k);
            if (elems == null) continue;

            for (Casing casing : casings) {
                enumerateSubstrOps(k, elems, casing, examples, out);
            }
        }
    }

    private static void enumerateSubstrOps(int k, List<String> elems, Casing casing,
                                            List<ExamplePair> examples, List<ScoredOp> out) {
        int minLen = minLength(elems);

        for (int start = 0; start < minLen; start++) {
            final int s = start;
            emitSubstrRange(elems, start, casing, examples,
                    len -> new SubstrOp(k, s, len, casing), out);
        }
    }

    private static void emitSubstrRange(List<String> elems, int start, Casing casing,
                                        List<ExamplePair> examples,
                                        java.util.function.IntFunction<LogicalOperator> opForLen,
                                        List<ScoredOp> out) {
        if (validAt(elems, examples, start, Integer.MAX_VALUE, casing)) {
            int score = 0;
            for (String e : elems) score += e.length() - start;
            out.add(new ScoredOp(opForLen.apply(-1), score));
        }

        int bound = Math.min(maxLength(elems) - start, MAX_SUBSTR_LEN);
        int lo = 0, hi = bound;
        while (lo < hi) {
            int mid = lo + (hi - lo + 1) / 2;
            if (validAt(elems, examples, start, mid, casing)) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        for (int len = 1; len <= lo; len++) {
            int score = 0;
            for (String e : elems) score += Math.min(len, e.length() - start);
            out.add(new ScoredOp(opForLen.apply(len), score));
        }
    }

    private static boolean validAt(List<String> elems, List<ExamplePair> examples,
                                   int start, int len, Casing casing) {
        for (int i = 0; i < elems.size(); i++) {
            String e = elems.get(i);
            int end = (int) Math.min((long) start + len, e.length());
            if (start >= end) return false;
            String result = casing.apply(e.substring(start, end));
            if (result.isEmpty() || !examples.get(i).targetValue.contains(result)) return false;
        }
        return true;
    }


    private static void addSplitSubstrCandidates(List<ExamplePair> examples,
                                                   List<ScoredOp> out, Set<Casing> casings) {
        int numCols = examples.get(0).sourceRow.length;

        for (int k = 0; k < numCols; k++) {
            List<String> elems = elementsAt(examples, k);
            if (elems == null) continue;

            Set<String> seps = extractSeparators(elems);
            for (String sep : seps) {
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
        int minLen = minLength(parts);
        for (int start = 0; start < minLen; start++) {
            final int s = start;
            emitSubstrRange(parts, start, casing, examples,
                    len -> new SplitSubstrOp(k, sep, m, s, len, casing), out);
        }
    }


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
        int minLen = minLength(finalParts);
        for (int start = 0; start < minLen; start++) {
            final int s = start;
            emitSubstrRange(finalParts, start, casing, examples,
                    len -> new SplitSplitSubstrOp(k1, sep1, k2, sep2, m, s, len, casing), out);
        }
    }


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

    private static int minLength(List<String> strings) {
        int min = Integer.MAX_VALUE;
        for (String s : strings) min = Math.min(min, s.length());
        return min;
    }

    private static int maxLength(List<String> strings) {
        int max = 0;
        for (String s : strings) max = Math.max(max, s.length());
        return max;
    }

    private static List<String> elementsAt(List<ExamplePair> examples, int k) {
        List<String> result = new ArrayList<>(examples.size());
        for (ExamplePair ex : examples) {
            if (k >= ex.sourceRow.length) return null;
            result.add(ex.sourceRow[k]);
        }
        return result;
    }

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

    private static int maxParts(List<String> elems, String sep) {
        int max = 0;
        for (String elem : elems) {
            max = Math.max(max, PhysicalOps.split(elem, sep).length);
        }
        return max;
    }

    static Set<String> extractSeparators(List<String> elems) {
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


    static final class ScoredOp {
        final LogicalOperator op;
        final int score;

        ScoredOp(LogicalOperator op, int score) {
            this.op = op;
            this.score = score;
        }
    }
}
