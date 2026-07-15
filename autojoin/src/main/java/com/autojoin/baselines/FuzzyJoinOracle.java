package com.autojoin.baselines;

import com.autojoin.model.Row;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;

public final class FuzzyJoinOracle implements JoinMethod {

    private static final String SEP = " ";

    public enum Tok { EXACT, LOWER, SPLIT, WORD, Q2, Q3, Q4, Q5, Q6, Q7, Q8, Q9, Q10 }
    public enum Dist { INTERSECT, JACCARD, DICE, MAXINCLUSION }

    public static final class Config {
        public final Tok tok;
        public final Dist dist;
        public final double threshold;   // distance cutoff in {0.1 .. 1.0}

        public Config(Tok tok, Dist dist, double threshold) {
            this.tok = tok;
            this.dist = dist;
            this.threshold = threshold;
        }

        public String label() {
            return tok + "/" + dist + "/t=" + String.format(java.util.Locale.US, "%.1f", threshold);
        }
    }

    public static List<Config> grid() {
        List<Config> configs = new ArrayList<>(520);
        for (Tok tok : Tok.values()) {
            for (Dist dist : Dist.values()) {
                for (int t = 1; t <= 10; t++) {
                    configs.add(new Config(tok, dist, t / 10.0));
                }
            }
        }
        return configs;
    }

    @Override
    public String name() { return "FJ-O"; }

    @Override
    public List<Row[]> join(JoinInput in) {
        return prepare(in).pairsAt(new Config(Tok.WORD, Dist.JACCARD, 0.5));
    }

    public static CaseEval prepare(JoinInput in) {
        return new CaseEval(in);
    }

    public static final class CaseEval {
        private final JoinInput in;
        private final List<String> srcVals;
        private final List<String> tgtVals;
        // bestIdx[tok][dist][srcRow] = closest target row; bestDist = its distance.
        private final int[][][] bestIdx = new int[Tok.values().length][Dist.values().length][];
        private final double[][][] bestDist = new double[Tok.values().length][Dist.values().length][];

        CaseEval(JoinInput in) {
            this.in = in;
            this.srcVals = new ArrayList<>(in.source.numRows());
            for (int i = 0; i < in.source.numRows(); i++) {
                srcVals.add(JoinMethod.joinValue(in.source.getRow(i), in.srcKeyCols, SEP));
            }
            this.tgtVals = new ArrayList<>(in.target.numRows());
            for (int j = 0; j < in.target.numRows(); j++) {
                tgtVals.add(JoinMethod.joinValue(in.target.getRow(j), in.tgtKeyCols, SEP));
            }
            for (Tok tok : Tok.values()) {
                List<Set<String>> srcTok = tokenizeAll(srcVals, tok);
                List<Set<String>> tgtTok = tokenizeAll(tgtVals, tok);
                for (Dist dist : Dist.values()) {
                    computeClosest(tok, dist, srcTok, tgtTok);
                }
            }
        }

        private void computeClosest(Tok tok, Dist dist,
                                    List<Set<String>> srcTok, List<Set<String>> tgtTok) {
            int ns = srcTok.size(), nt = tgtTok.size();
            int[] idx = new int[ns];
            double[] dst = new double[ns];
            for (int i = 0; i < ns; i++) {
                // Cooperative cancellation: the backend runs baselines under a
                // paper-style timeout (§6.4) and interrupts on expiry.
                if (Thread.currentThread().isInterrupted())
                    throw new CancellationException("FJ-O cancelled (timeout)");
                int best = -1;
                double bestD = Double.MAX_VALUE;
                Set<String> a = srcTok.get(i);
                for (int j = 0; j < nt; j++) {
                    double d = distance(a, tgtTok.get(j), dist);
                    if (d < bestD) { bestD = d; best = j; }
                }
                idx[i] = best;
                dst[i] = best < 0 ? Double.MAX_VALUE : bestD;
            }
            bestIdx[tok.ordinal()][dist.ordinal()] = idx;
            bestDist[tok.ordinal()][dist.ordinal()] = dst;
        }

        public List<Row[]> pairsAt(Config cfg) {
            int[] idx = bestIdx[cfg.tok.ordinal()][cfg.dist.ordinal()];
            double[] dst = bestDist[cfg.tok.ordinal()][cfg.dist.ordinal()];
            double cutoff = cfg.dist == Dist.INTERSECT
                    ? 1.0 / (1.0 + Math.round(cfg.threshold * 10)) + 1e-12
                    : cfg.threshold;
            List<Row[]> pairs = new ArrayList<>();
            for (int i = 0; i < idx.length; i++) {
                if (idx[i] >= 0 && dst[i] <= cutoff) {
                    pairs.add(new Row[]{in.source.getRow(i), in.target.getRow(idx[i])});
                }
            }
            return pairs;
        }
    }


    private static List<Set<String>> tokenizeAll(List<String> vals, Tok tok) {
        List<Set<String>> out = new ArrayList<>(vals.size());
        for (String v : vals) {
            if (Thread.currentThread().isInterrupted())
                throw new CancellationException("FJ-O cancelled (timeout)");
            out.add(tokenize(v == null ? "" : v, tok));
        }
        return out;
    }

    private static Set<String> tokenize(String s, Tok tok) {
        Set<String> set = new HashSet<>();
        switch (tok) {
            case EXACT: set.add(s); return set;
            case LOWER: set.add(s.toLowerCase()); return set;
            case SPLIT:
                for (String p : s.toLowerCase().split("\\s+"))
                    if (!p.isEmpty()) set.add(p);
                return set;
            case WORD:
                for (String w : s.toLowerCase().replaceAll("[^\\p{Alnum}]+", " ").split("\\s+"))
                    if (!w.isEmpty()) set.add(w);
                return set;
            default:
                int q = qOf(tok);
                String pad = "$".repeat(q - 1);
                // NB: replaceAll's replacement treats '$' as a group reference,
                // so the padding must be inserted literally.
                String t = pad + s.toLowerCase().replaceAll("\\s",
                        java.util.regex.Matcher.quoteReplacement(pad)) + pad;
                if (t.length() < q) { set.add(t); return set; }
                for (int i = 0; i <= t.length() - q; i++) set.add(t.substring(i, i + q));
                return set;
        }
    }

    private static int qOf(Tok tok) {
        switch (tok) {
            case Q2: return 2; case Q3: return 3; case Q4: return 4; case Q5: return 5;
            case Q6: return 6; case Q7: return 7; case Q8: return 8; case Q9: return 9;
            case Q10: return 10; default: return 3;
        }
    }

    private static double distance(Set<String> a, Set<String> b, Dist dist) {
        if (a.isEmpty() && b.isEmpty()) return 0.0;
        if (a.isEmpty() || b.isEmpty()) return 1.0;
        Set<String> small = a.size() <= b.size() ? a : b;
        Set<String> large = small == a ? b : a;
        int inter = 0;
        for (String g : small) if (large.contains(g)) inter++;
        switch (dist) {
            case JACCARD:      return 1.0 - (double) inter / (a.size() + b.size() - inter);
            case DICE:         return 1.0 - 2.0 * inter / (a.size() + b.size());
            case MAXINCLUSION: return 1.0 - (double) inter / Math.min(a.size(), b.size());
            case INTERSECT:    return 1.0 / (1.0 + inter);
            default:           return 1.0;
        }
    }
}
