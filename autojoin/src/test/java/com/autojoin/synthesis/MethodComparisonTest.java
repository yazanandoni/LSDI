package com.autojoin.synthesis;

import com.autojoin.AutoJoin;
import com.autojoin.JoinResult;
import com.autojoin.baselines.FuzzyJoinColumn;
import com.autojoin.baselines.FuzzyJoinOracle;
import com.autojoin.baselines.JoinMethod;
import com.autojoin.baselines.SubstringMatching;
import com.autojoin.model.Row;
import com.autojoin.model.Table;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Quality comparison of Auto-Join against the paper's baselines SM, FJ-C and
 * FJ-O on the Web benchmark — reproduces Figure 5b (average precision/recall per
 * method) on local hardware, so the four methods are compared on identical data
 * and scoring.
 *
 * Gated behind {@code -Dcompare=true} because it runs the full Auto-Join
 * pipeline plus a 520-config FJ-O grid over every fixture, which is far heavier
 * than a normal unit test. Run with:
 *
 *   mvn -q -Dtest=MethodComparisonTest -Dcompare=true test
 *
 * Precision/recall follow the paper (§6.1): precision is |G∩J|/|J|, recall is
 * |G∩J|/|G| over SETS of (sourceKey, targetKey) row pairs; per-method averages
 * take precision over non-empty cases only, recall over all cases with ground
 * truth, and F is the harmonic mean of the two averages.
 */
class MethodComparisonTest {

    @Test
    void compareMethods() throws IOException {
        Assumptions.assumeTrue(Boolean.getBoolean("compare"),
                "set -Dcompare=true to run the method comparison");

        List<Case> cases = loadCases();

        Map<String, List<Metrics>> byMethod = new LinkedHashMap<>();
        byMethod.put("AJ", new ArrayList<>());
        List<JoinMethod> baselines = List.of(
                new SubstringMatching(), new FuzzyJoinColumn());
        for (JoinMethod m : baselines) byMethod.put(m.name(), new ArrayList<>());

        AutoJoin autoJoin = new AutoJoin();
        // FJ-O grid: prepare each case once, then pick the single config with the
        // highest average per-case F across all cases (the paper's oracle rule).
        List<FuzzyJoinOracle.CaseEval> oracleEvals = new ArrayList<>(cases.size());
        for (Case c : cases) {
            oracleEvals.add(FuzzyJoinOracle.prepare(
                    new JoinMethod.JoinInput(c.source, c.target, c.srcKeyCols, c.tgtKeyCols)));
        }
        FuzzyJoinOracle.Config oracleCfg = pickOracleConfig(cases, oracleEvals);

        for (int i = 0; i < cases.size(); i++) {
            Case c = cases.get(i);
            JoinMethod.JoinInput in =
                    new JoinMethod.JoinInput(c.source, c.target, c.srcKeyCols, c.tgtKeyCols);

            JoinResult aj = autoJoin.join(c.source, c.target);
            boolean fwd = BenchmarkTestHelper.isForwardDirection(aj, c.srcKeyCols);
            byMethod.get("AJ").add(c.scorer.scoreRows(aj.getJoinedPairs(), fwd));

            for (JoinMethod m : baselines) {
                byMethod.get(m.name()).add(c.scorer.scoreRows(m.join(in), true));
            }
        }

        // FJ-O under the chosen global config.
        List<Metrics> fjo = new ArrayList<>(cases.size());
        for (int i = 0; i < cases.size(); i++) {
            fjo.add(cases.get(i).scorer.scoreRows(oracleEvals.get(i).pairsAt(oracleCfg), true));
        }
        byMethod.put("FJ-O", fjo);

        printReport(cases, byMethod, oracleCfg);
    }

    /** Choose the FJ-O config maximizing average per-case F over all gt-cases. */
    private static FuzzyJoinOracle.Config pickOracleConfig(
            List<Case> cases, List<FuzzyJoinOracle.CaseEval> evals) {
        FuzzyJoinOracle.Config best = null;
        double bestF = -1;
        for (FuzzyJoinOracle.Config cfg : FuzzyJoinOracle.grid()) {
            double sumF = 0;
            int n = 0;
            for (int i = 0; i < cases.size(); i++) {
                Case c = cases.get(i);
                if (c.scorer.gtSize == 0) continue;
                sumF += c.scorer.scoreRows(evals.get(i).pairsAt(cfg), true).f();
                n++;
            }
            double avgF = n == 0 ? 0 : sumF / n;
            if (avgF > bestF) { bestF = avgF; best = cfg; }
        }
        return best;
    }

    private void printReport(List<Case> cases, Map<String, List<Metrics>> byMethod,
                             FuzzyJoinOracle.Config oracleCfg) {
        List<String> methods = new ArrayList<>(byMethod.keySet());

        System.out.println();
        System.out.println("=========== METHOD COMPARISON - Web benchmark (" + cases.size() + " cases) ===========");
        System.out.println("FJ-O oracle config: " + oracleCfg.label());
        System.out.println();

        System.out.printf(Locale.US, "%-26s", "case");
        for (String m : methods) System.out.printf(Locale.US, " %6s", m);
        System.out.println();
        System.out.println("--------------------------------------------------------------------");
        for (int i = 0; i < cases.size(); i++) {
            System.out.printf(Locale.US, "%-26s", trim(cases.get(i).pairId));
            for (String m : methods) System.out.printf(Locale.US, " %6.2f", byMethod.get(m).get(i).f());
            System.out.println();
        }
        System.out.println("--------------------------------------------------------------------");

        System.out.printf(Locale.US, "%-8s %8s %8s %8s%n", "method", "prec", "recall", "F");
        for (String m : methods) {
            Agg a = aggregate(byMethod.get(m));
            System.out.printf(Locale.US, "%-8s %8.3f %8.3f %8.3f%n", m, a.prec, a.recall, a.f);
        }
        System.out.println("====================================================================");
    }

    private static Agg aggregate(List<Metrics> ms) {
        double precSum = 0; int precN = 0;
        double recSum = 0; int recN = 0;
        for (Metrics m : ms) {
            if (!m.hasGt) continue;
            recSum += m.recall(); recN++;
            if (!m.empty()) { precSum += m.precision(); precN++; }
        }
        double prec = precN == 0 ? 0 : precSum / precN;
        double rec = recN == 0 ? 0 : recSum / recN;
        double f = (prec + rec) == 0 ? 0 : 2 * prec * rec / (prec + rec);
        return new Agg(prec, rec, f);
    }

    private static String trim(String s) { return s.length() > 26 ? s.substring(0, 26) : s; }

    // ----- case loading -----

    private static List<Case> loadCases() throws IOException {
        List<Case> cases = new ArrayList<>();
        for (String pairId : BenchmarkTestHelper.listFixtures()) {
            BenchmarkFixture fx = BenchmarkTestHelper.loadFixture(pairId);
            Table source = BenchmarkTestHelper.loadTable(
                    fx.source.file, "source-" + fx.pair_id, fx.source.key_columns);
            Table target = BenchmarkTestHelper.loadTable(
                    fx.target.file, "target-" + fx.pair_id, fx.target.key_columns);
            Path gtPath = Paths.get("").resolve(fx.ground_truth.file);
            Map<String, List<String>> gtMap = BenchmarkTestHelper.loadGroundTruth(
                    gtPath, fx.ground_truth.source_key_columns.size());
            cases.add(new Case(pairId, source, target,
                    fx.source.key_columns, fx.target.key_columns,
                    new Scorer(fx.source.key_columns, fx.target.key_columns, gtMap)));
        }
        return cases;
    }

    private static final class Case {
        final String pairId;
        final Table source, target;
        final List<String> srcKeyCols, tgtKeyCols;
        final Scorer scorer;

        Case(String pairId, Table source, Table target,
             List<String> srcKeyCols, List<String> tgtKeyCols, Scorer scorer) {
            this.pairId = pairId;
            this.source = source;
            this.target = target;
            this.srcKeyCols = srcKeyCols;
            this.tgtKeyCols = tgtKeyCols;
            this.scorer = scorer;
        }
    }

    /**
     * Scores a method's joined pairs against ground truth exactly as
     * {@link BenchmarkIntegrationTest}, but set-based per the paper: a produced
     * pair is its (srcKey, tgtKey) fingerprint, deduped so duplicate source keys
     * cannot inflate recall past 1.
     */
    private static final class Scorer {
        final Map<String, List<String>> gtMap;
        final int gtSize;
        private final List<String> srcKeyCols, tgtKeyCols;
        private static final String SEP = "	";

        Scorer(List<String> srcKeyCols, List<String> tgtKeyCols,
               Map<String, List<String>> gtMap) {
            this.gtMap = gtMap;
            this.gtSize = gtMap.size();
            this.srcKeyCols = srcKeyCols;
            this.tgtKeyCols = tgtKeyCols;
        }

        Metrics scoreRows(List<Row[]> pairs, boolean forward) {
            boolean hasGt = gtSize > 0;
            Set<String> produced = new HashSet<>();
            int tp = 0;
            for (Row[] pair : pairs) {
                Row srcRow = forward ? pair[0] : pair[1];
                Row tgtRow = forward ? pair[1] : pair[0];
                String srcFp = BenchmarkTestHelper.positionalFingerprint(srcRow, srcKeyCols, "|");
                String tgtFp = BenchmarkTestHelper.positionalFingerprint(tgtRow, tgtKeyCols, " | ");
                if (!produced.add(srcFp + SEP + tgtFp)) continue; // same pair already counted
                List<String> expected = gtMap.get(srcFp);
                if (expected != null && tgtFp.equals(String.join(" | ", expected))) tp++;
            }
            return new Metrics(produced.size(), tp, gtSize, hasGt);
        }
    }

    private static final class Metrics {
        final int joined, tp, gtSize;
        final boolean hasGt;

        Metrics(int joined, int tp, int gtSize, boolean hasGt) {
            this.joined = joined;
            this.tp = tp;
            this.gtSize = gtSize;
            this.hasGt = hasGt;
        }

        boolean empty() { return joined == 0; }
        double precision() { return joined == 0 ? 0 : (double) tp / joined; }
        double recall() { return gtSize == 0 ? 0 : (double) tp / gtSize; }
        double f() {
            double p = precision(), r = recall();
            return (p + r) == 0 ? 0 : 2 * p * r / (p + r);
        }
    }

    private static final class Agg {
        final double prec, recall, f;
        Agg(double prec, double recall, double f) { this.prec = prec; this.recall = recall; this.f = f; }
    }
}
