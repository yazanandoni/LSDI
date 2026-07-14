package com.autojoin.baselines;

import com.autojoin.model.Row;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;

/**
 * FJ-O — Fuzzy Join, Oracle (paper §6.2).
 *
 * "To be more favorable to fuzzy join based methods, we consider an extensive
 * combination of configurations. For tokenization we use {Exact, Lower, Split,
 * Word, and q-gram for q in [2,10]}; for distance functions {Intersect,
 * Jaccard, Dice, MaxInclusion}; and for thresholds 10 equally-distanced values.
 * This creates a total of 520 unique parameter configurations. We execute each
 * of these fuzzy joins on columns that are used in the ground truth as if they
 * are known a priori, and we join each row with top-1 fuzzy match in the other
 * table to maintain high precision. We report the best configuration that has
 * the highest average F-score across all cases."
 *
 * 13 tokenizations × 4 distances × 10 thresholds = 520 configs. Because the
 * winning configuration is chosen using the ground truth (and applied globally
 * across every case), FJ-O is an *oracle* upper bound on fuzzy-join quality,
 * not a usable method. The quality harness drives the grid; {@link #join}
 * exposes a single fixed config so FJ-O can also be timed standalone.
 *
 * <p>Note that {@link #join} still pays the FULL 13×4 grid (the eager
 * precompute in {@link CaseEval}) — deliberately. FJ-O's cost IS the grid
 * search over configurations; that is why in the paper's Figure 8 FJ-O times
 * out at 10K rows while the single-configuration FJ-C survives until 100K.
 * Making this lazy would make FJ-O's timing indistinguishable from FJ-C's and
 * lose that ordering.
 */
public final class FuzzyJoinOracle implements JoinMethod {

    private static final String SEP = " ";

    public enum Tok { EXACT, LOWER, SPLIT, WORD, Q2, Q3, Q4, Q5, Q6, Q7, Q8, Q9, Q10 }
    public enum Dist { INTERSECT, JACCARD, DICE, MAXINCLUSION }

    /** One point in the 520-configuration grid. */
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

    /** The full 13 × 4 × 10 = 520 configuration grid. */
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

    /**
     * Standalone/timing use only: a single representative config
     * (word/Jaccard/0.5). This is NOT the oracle — quality comparisons must
     * drive the grid via {@link #prepare} and pick the config with the ground
     * truth (the backend's BenchmarkService and MethodComparisonTest both do);
     * this entry point exists for the DBLP scalability runs, where FJ-O is
     * only timed and the config choice does not change the grid cost.
     */
    @Override
    public List<Row[]> join(JoinInput in) {
        return prepare(in).pairsAt(new Config(Tok.WORD, Dist.JACCARD, 0.5));
    }

    /**
     * Precompute, for one case, the top-1 (closest) target row for every source
     * row under each (tokenization, distance) pair. The closest match does not
     * depend on the threshold, so all 10 thresholds of a (tok, dist) pair reuse
     * one all-pairs pass — the grid over a case costs 13 × 4 distance sweeps,
     * not 520.
     */
    public static CaseEval prepare(JoinInput in) {
        return new CaseEval(in);
    }

    /** Precomputed closest-match state for one case, queried per Config. */
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

        /** Top-1 joined pairs under {@code cfg}: each source row joins its closest
         *  target when that distance is within the threshold. INTERSECT is ref
         *  [17]'s raw intersection COUNT, so its 10 "equally-distanced" threshold
         *  values are the counts 1..10 (distance is encoded as 1/(1+count)). */
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

    // ----- tokenization + distance -----

    private static List<Set<String>> tokenizeAll(List<String> vals, Tok tok) {
        List<Set<String>> out = new ArrayList<>(vals.size());
        for (String v : vals) {
            if (Thread.currentThread().isInterrupted())
                throw new CancellationException("FJ-O cancelled (timeout)");
            out.add(tokenize(v == null ? "" : v, tok));
        }
        return out;
    }

    /**
     * The lexical analyzers of ref [17] (Hassanzadeh et al., "Discovering
     * linkage points over web data", PVLDB 2013 — the paper AutoJoin's sec. 6.2
     * cites for this tokenization space), implemented as defined there:
     * "Exact... does not make any change to the string. Lower... turns the
     * string value into lowercase. Split... breaks the string into a set of
     * tokens by splitting them by whitespace after using the lower analyzer
     * (IBM Corp. -> ibm, corp.). Word token... first replaces all the
     * non-alphanumeric characters with whitespace and then uses the split
     * tokenizer (http://ibm.com -> http, ibm, com). The q-gram analyzer
     * tokenizes the string into the set of all lowercase substrings of length
     * q. It also replaces all whitespaces with q-1 occurrences of a special
     * character such as $" — and, per its worked example ($$i, $ib, ibm, ...),
     * pads both ends of the string with q-1 of them too.
     */
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

    /**
     * The similarity functions of ref [17] (Eqs. 4-7), expressed as distances
     * so that smaller = closer: jaccard = |∩|/|∪|, dice = 2|∩|/(|V1|+|V2|),
     * maxinc = |∩|/min(|V1|,|V2|) ("the maximum inclusion degree of one value
     * set in another") — each mapped to 1 - similarity — and intersect = |∩|
     * (the raw intersection size), mapped to 1/(1+|∩|) so its ordering is
     * monotone; {@link CaseEval#pairsAt} translates INTERSECT thresholds into
     * the counts 1..10.
     */
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
