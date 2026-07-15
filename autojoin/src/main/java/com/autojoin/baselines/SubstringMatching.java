package com.autojoin.baselines;

import com.autojoin.model.Row;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;


public final class SubstringMatching implements JoinMethod {

    private static final int Q = 3;

    private static final double SAMPLE_FRACTION = 0.10;

    private static final int MIN_SAMPLE = 20;
    private static final int MAX_SAMPLE = 400;

    private static final int TOP_R = 5;

    private static final int MIN_VOTES = 2;

    private static final int MAX_REGION_ITERATIONS = 8;

    private static final int MAX_PASSES = 3;

    private static final boolean DEBUG = Boolean.getBoolean("autojoin.sm.debug");

    @Override
    public String name() { return "SM"; }


    private record Seg(int kind, int col, int start, int len, boolean toEnd, String lit) {
        static final int COPY = 0, LIT = 1, UNKNOWN = 2;
        static Seg copy(int col, int start, int len, boolean toEnd) { return new Seg(COPY, col, start, len, toEnd, null); }
        static Seg lit(String s) { return new Seg(LIT, 0, 0, 0, false, s); }
        static Seg unknown() { return new Seg(UNKNOWN, 0, 0, 0, false, null); }
    }

    private record Recipe(int u, int col, int bStart, int lenOrEnd,
                          boolean gapBefore, boolean gapAfter) {}

    @Override
    public List<Row[]> join(JoinInput in) {
        List<String[]> src = new ArrayList<>(in.source.numRows());
        int cols = 0;
        for (int i = 0; i < in.source.numRows(); i++) {
            List<String> vals = in.source.getRow(i).getValues();
            cols = Math.max(cols, vals.size());
            src.add(vals.toArray(new String[0]));
        }
        for (int i = 0; i < src.size(); i++) {   // pad ragged rows
            if (src.get(i).length < cols) {
                String[] padded = new String[cols];
                System.arraycopy(src.get(i), 0, padded, 0, src.get(i).length);
                for (int c = src.get(i).length; c < cols; c++) padded[c] = "";
                src.set(i, padded);
            }
        }
        List<String> tgt = new ArrayList<>(in.target.numRows());
        for (int j = 0; j < in.target.numRows(); j++) {
            tgt.add(JoinMethod.joinValue(in.target.getRow(j), in.tgtKeyCols, " "));
        }

        List<Row[]> pairs = new ArrayList<>();
        List<Integer> srcLeft = new ArrayList<>(), tgtLeft = new ArrayList<>();
        for (int i = 0; i < src.size(); i++) srcLeft.add(i);
        for (int j = 0; j < tgt.size(); j++) tgtLeft.add(j);

        for (int pass = 0; pass < MAX_PASSES && !srcLeft.isEmpty() && !tgtLeft.isEmpty(); pass++) {
            List<Seg> formula = learnFormula(src, tgt, srcLeft, tgtLeft, cols);
            if (formula == null) break;

            Map<String, Integer> tgtIndex = new HashMap<>();
            for (int j : tgtLeft) tgtIndex.putIfAbsent(tgt.get(j), j);

            List<Integer> srcNext = new ArrayList<>();
            Set<Integer> tgtMatched = new HashSet<>();
            int matched = 0;
            for (int i : srcLeft) {
                String derived = applyFormula(formula, src.get(i));
                Integer j = derived == null ? null : tgtIndex.get(derived);
                if (j != null) {
                    pairs.add(new Row[]{in.source.getRow(i), in.target.getRow(j)});
                    tgtMatched.add(j);
                    matched++;
                } else {
                    srcNext.add(i);
                }
            }
            if (matched == 0) break;  // this pass found nothing new
            srcLeft = srcNext;
            List<Integer> tgtNext = new ArrayList<>();
            for (int j : tgtLeft) if (!tgtMatched.contains(j)) tgtNext.add(j);
            tgtLeft = tgtNext;
        }
        return pairs;
    }


    private List<Seg> learnFormula(List<String[]> src, List<String> tgt,
                                   List<Integer> srcRows, List<Integer> tgtRows, int cols) {
        int bstart = -1;
        double bestScore = 0;
        for (int c = 0; c < cols; c++) {
            double s = scoreColumn(src, tgt, srcRows, tgtRows, c);
            if (s > bestScore) { bestScore = s; bstart = c; }
        }
        if (DEBUG) System.err.println("[SM] bstart=" + bstart + " score=" + bestScore);
        if (bstart < 0) return null;

        Map<String, Double> idf = buildIdf(tgt, tgtRows);

        List<Seg> formula = new ArrayList<>();
        formula.add(Seg.unknown());

        List<Integer> sample = interleavedSample(srcRows);
        for (int iter = 0; iter < MAX_REGION_ITERATIONS && hasUnknown(formula); iter++) {
            Map<Recipe, Integer> votes = new HashMap<>();
            Set<Integer> contributing = new HashSet<>();
            boolean firstIter = iter == 0;
            for (int c = firstIter ? bstart : 0; c < (firstIter ? bstart + 1 : cols); c++) {
                for (int row : sample) {
                    checkCancelled();
                    String b = src.get(row)[c];
                    if (b == null || b.length() < 2) continue;
                    List<String> candidates = firstIter
                            ? similarTargets(b, tgt, tgtRows, idf)
                            : consistentTargets(formula, src.get(row), tgt, tgtRows);
                    if (!candidates.isEmpty()) contributing.add(row);
                    for (String a : candidates) {
                        collectRecipes(formula, src.get(row), a, c, b, firstIter, votes);
                    }
                }
            }
            Recipe best = null;
            int bestVotes = 0;
            for (Map.Entry<Recipe, Integer> e : votes.entrySet()) {
                if (e.getValue() > bestVotes) { bestVotes = e.getValue(); best = e.getKey(); }
            }
            int needed = Math.max(MIN_VOTES, (contributing.size() + 1) / 2);
            if (DEBUG) System.err.println("[SM] iter=" + iter + " votes=" + votes.size()
                    + " best=" + best + " x" + bestVotes + "/" + needed
                    + " formula=" + describe(formula));
            if (best == null || bestVotes < needed) break;
            splice(formula, best);
        }

        boolean literalsOk = resolveLiterals(formula, src, tgt, sample, tgtRows);
        if (DEBUG) System.err.println("[SM] literals=" + literalsOk + " final=" + describe(formula));
        if (!literalsOk) return null;
        for (Seg s : formula) if (s.kind() == Seg.COPY) return formula;
        return null;
    }

    private double scoreColumn(List<String[]> src, List<String> tgt,
                               List<Integer> srcRows, List<Integer> tgtRows, int c) {
        LinkedHashSet<String> distinct = new LinkedHashSet<>();
        for (int row : srcRows) {
            String v = src.get(row)[c];
            if (v != null && v.length() >= Q) distinct.add(v);
        }
        if (distinct.isEmpty()) return 0;
        List<String> values = new ArrayList<>(distinct);
        List<Integer> keyIdx = interleavedSample(indices(values.size()));
        List<Integer> tgtSample = interleavedSample(tgtRows);
        double acc = 0;
        for (int vi : keyIdx) {
            String key = values.get(vi);
            int best = 0;
            for (int j : tgtSample) {
                checkCancelled();
                int[] lcs = longestCommonSubstring(key, tgt.get(j));
                if (lcs != null && lcs[2] > best) best = lcs[2];
                if (best == key.length()) break;   // cannot improve
            }
            if (best >= Q) acc += best;
        }
        return Math.pow(acc / keyIdx.size(), Q);
    }

    private List<String> similarTargets(String b, List<String> tgt,
                                        List<Integer> tgtRows, Map<String, Double> idf) {
        Set<String> grams = qGrams(b);
        if (grams.isEmpty()) return List.of();
        List<String> best = new ArrayList<>();
        List<Double> bestScores = new ArrayList<>();
        for (int j : tgtRows) {
            checkCancelled();
            String a = tgt.get(j);
            double score = 0;
            for (String g : grams) {
                if (a.contains(g)) {
                    Double w = idf.get(g);
                    if (w != null) score += w * w;
                }
            }
            if (score <= 0) continue;
            int pos = 0;
            while (pos < bestScores.size() && bestScores.get(pos) >= score) pos++;
            if (pos < TOP_R) {
                best.add(pos, a);
                bestScores.add(pos, score);
                if (best.size() > TOP_R) { best.remove(TOP_R); bestScores.remove(TOP_R); }
            }
        }
        return best;
    }

    private List<String> consistentTargets(List<Seg> formula, String[] rowVals,
                                           List<String> tgt, List<Integer> tgtRows) {
        List<String> out = new ArrayList<>();
        for (int j : tgtRows) {
            checkCancelled();
            if (unknownGaps(formula, rowVals, tgt.get(j)) != null) out.add(tgt.get(j));
            if (out.size() >= TOP_R) break;
        }
        return out;
    }

    private void collectRecipes(List<Seg> formula, String[] rowVals, String a,
                                int col, String b, boolean firstIter,
                                Map<Recipe, Integer> votes) {
        List<int[]> gaps = unknownGaps(formula, rowVals, a); // {unknownIdx, gapStart, gapEnd}
        if (gaps == null) return;
        int minLen = firstIter ? Q : 1;
        for (int[] g : gaps) {
            String gap = a.substring(g[1], g[2]);
            int[] lcs = longestCommonSubstring(b, gap);
            if (lcs == null || lcs[2] < minLen) continue;
            boolean before = lcs[1] > 0;
            boolean after = lcs[1] + lcs[2] < gap.length();
            votes.merge(new Recipe(g[0], col, lcs[0], lcs[2], before, after), 1, Integer::sum);
            if (lcs[0] + lcs[2] == b.length()) {
                votes.merge(new Recipe(g[0], col, lcs[0], -1, before, after), 1, Integer::sum);
            }
        }
    }

    private void splice(List<Seg> formula, Recipe r) {
        int seen = -1;
        for (int i = 0; i < formula.size(); i++) {
            if (formula.get(i).kind() != Seg.UNKNOWN) continue;
            if (++seen != r.u()) continue;
            formula.remove(i);
            List<Seg> repl = new ArrayList<>();
            if (r.gapBefore()) repl.add(Seg.unknown());
            repl.add(r.lenOrEnd() < 0
                    ? Seg.copy(r.col(), r.bStart(), 0, true)
                    : Seg.copy(r.col(), r.bStart(), r.lenOrEnd(), false));
            if (r.gapAfter()) repl.add(Seg.unknown());
            formula.addAll(i, repl);
            return;
        }
    }

    private boolean resolveLiterals(List<Seg> formula, List<String[]> src, List<String> tgt,
                                    List<Integer> sample, List<Integer> tgtRows) {
        if (!hasUnknown(formula)) return true;
        int unknowns = 0;
        for (Seg s : formula) if (s.kind() == Seg.UNKNOWN) unknowns++;
        List<Map<String, Integer>> gapVotes = new ArrayList<>();
        for (int u = 0; u < unknowns; u++) gapVotes.add(new HashMap<>());
        int consistent = 0;
        for (int row : sample) {
            for (int j : tgtRows) {
                checkCancelled();
                List<int[]> gaps = unknownGaps(formula, src.get(row), tgt.get(j));
                if (gaps == null || gaps.size() != unknowns) continue;
                for (int[] g : gaps) {
                    gapVotes.get(g[0]).merge(tgt.get(j).substring(g[1], g[2]), 1, Integer::sum);
                }
                consistent++;
                break;
            }
        }
        if (consistent < MIN_VOTES) return false;
        String[] literals = new String[unknowns];
        for (int u = 0; u < unknowns; u++) {
            String best = null;
            int bestVotes = 0;
            for (Map.Entry<String, Integer> e : gapVotes.get(u).entrySet()) {
                if (e.getValue() > bestVotes) { bestVotes = e.getValue(); best = e.getKey(); }
            }
            if (best == null || bestVotes < Math.max(MIN_VOTES, consistent / 2)) return false;
            literals[u] = best;
        }
        int u = 0;
        for (int i = 0; i < formula.size(); i++) {
            if (formula.get(i).kind() == Seg.UNKNOWN) {
                formula.set(i, Seg.lit(literals[u++]));
            }
        }
        return true;
    }


    private List<int[]> unknownGaps(List<Seg> formula, String[] rowVals, String a) {
        List<int[]> gaps = new ArrayList<>();
        int pos = 0, u = 0;
        int pendingUnknown = -1;
        for (Seg s : formula) {
            if (s.kind() == Seg.UNKNOWN) {
                pendingUnknown = u++;
                continue;
            }
            String piece = s.kind() == Seg.LIT ? s.lit() : copyValue(s, rowVals);
            if (piece == null || piece.isEmpty()) return null;
            int idx = a.indexOf(piece, pos);
            if (idx < 0) return null;
            if (pendingUnknown >= 0) {
                gaps.add(new int[]{pendingUnknown, pos, idx});
                pendingUnknown = -1;
            } else if (idx != pos) {
                return null;
            }
            pos = idx + piece.length();
        }
        if (pendingUnknown >= 0) {
            gaps.add(new int[]{pendingUnknown, pos, a.length()});
        } else if (pos != a.length()) {
            return null;
        }
        return gaps;
    }

    private String applyFormula(List<Seg> formula, String[] rowVals) {
        StringBuilder sb = new StringBuilder();
        for (Seg s : formula) {
            if (s.kind() == Seg.UNKNOWN) return null;
            String piece = s.kind() == Seg.LIT ? s.lit() : copyValue(s, rowVals);
            if (piece == null || piece.isEmpty()) return null;
            sb.append(piece);
        }
        return sb.toString();
    }

    private String copyValue(Seg s, String[] rowVals) {
        String v = rowVals[s.col()];
        if (v == null || s.start() >= v.length()) return null;
        return s.toEnd() ? v.substring(s.start())
                         : (s.start() + s.len() <= v.length()
                                ? v.substring(s.start(), s.start() + s.len()) : null);
    }

    private int[] longestCommonSubstring(String b, String gap) {
        int nb = b.length(), ng = gap.length();
        if (nb == 0 || ng == 0) return null;
        int[] prev = new int[ng + 1], cur = new int[ng + 1];
        int best = 0, bPos = -1, gPos = -1;
        for (int i = 1; i <= nb; i++) {
            checkCancelled();
            for (int j = 1; j <= ng; j++) {
                if (b.charAt(i - 1) == gap.charAt(j - 1)) {
                    cur[j] = prev[j - 1] + 1;
                    if (cur[j] > best) { best = cur[j]; bPos = i - best; gPos = j - best; }
                } else {
                    cur[j] = 0;
                }
            }
            int[] t = prev; prev = cur; cur = t;
            java.util.Arrays.fill(cur, 0);
        }
        return best == 0 ? null : new int[]{bPos, gPos, best};
    }


    private static String describe(List<Seg> formula) {
        StringBuilder sb = new StringBuilder("[");
        for (Seg s : formula) {
            if (sb.length() > 1) sb.append(" + ");
            switch (s.kind()) {
                case Seg.COPY -> sb.append("B").append(s.col()).append('[').append(s.start())
                        .append("..").append(s.toEnd() ? "n" : s.start() + s.len()).append(']');
                case Seg.LIT -> sb.append('"').append(s.lit()).append('"');
                default -> sb.append('%');
            }
        }
        return sb.append(']').toString();
    }

    private static boolean hasUnknown(List<Seg> formula) {
        for (Seg s : formula) if (s.kind() == Seg.UNKNOWN) return true;
        return false;
    }

    private Map<String, Double> buildIdf(List<String> tgt, List<Integer> tgtRows) {
        Map<String, Integer> df = new HashMap<>();
        for (int j : tgtRows) {
            checkCancelled();
            for (String g : qGrams(tgt.get(j))) df.merge(g, 1, Integer::sum);
        }
        Map<String, Double> idf = new HashMap<>(df.size());
        double n = tgtRows.size();
        for (Map.Entry<String, Integer> e : df.entrySet()) {
            idf.put(e.getKey(), Math.log(n / e.getValue()) / Math.log(2));
        }
        return idf;
    }

    private static Set<String> qGrams(String s) {
        Set<String> grams = new HashSet<>();
        for (int i = 0; i + Q <= s.length(); i++) grams.add(s.substring(i, i + Q));
        return grams;
    }

    private static List<Integer> interleavedSample(List<Integer> rows) {
        int t = (int) Math.ceil(rows.size() * SAMPLE_FRACTION);
        t = Math.max(Math.min(MIN_SAMPLE, rows.size()), Math.min(t, MAX_SAMPLE));
        List<Integer> out = new ArrayList<>(t);
        double step = rows.size() / (double) t;
        for (int i = 0; i < t; i++) out.add(rows.get((int) (i * step)));
        return out;
    }

    private static List<Integer> indices(int n) {
        List<Integer> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) out.add(i);
        return out;
    }

    private static void checkCancelled() {
        if (Thread.currentThread().isInterrupted())
            throw new CancellationException("SM cancelled (timeout)");
    }
}
