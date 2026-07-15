package com.autojoin.synthesis;

import com.autojoin.AutoJoin;
import com.autojoin.JoinResult;
import com.autojoin.model.Row;
import com.autojoin.model.Table;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertNotNull;

class BenchmarkIntegrationTest {

    private final AutoJoin autoJoin = new AutoJoin();

    private static final List<Metrics> RESULTS = new ArrayList<>();

    @TestFactory
    Stream<DynamicTest> allWebBenchmarks() throws IOException {
        List<String> filters = parseOnlyFilter();
        List<String> fixtures = BenchmarkTestHelper.listFixtures().stream()
                .filter(pairId -> matchesFilter(pairId, filters))
                .collect(Collectors.toList());

        if (!filters.isEmpty()) {
            System.out.println("[benchmark.only] running " + fixtures.size()
                    + " of matched fixture(s): " + fixtures);
        }

        return fixtures.stream()
                .map(pairId -> DynamicTest.dynamicTest(pairId, () -> {
                    Metrics m = run(pairId);
                    RESULTS.add(m);
                    assertNotNull(m, pairId + ": metrics is null");
                }));
    }

    private static List<String> parseOnlyFilter() {
        String only = System.getProperty("benchmark.only", "").trim();
        if (only.isEmpty()) return List.of();
        return Arrays.stream(only.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(s -> s.toLowerCase())
                .collect(Collectors.toList());
    }

    private static boolean matchesFilter(String pairId, List<String> filters) {
        if (filters.isEmpty()) return true;
        String lower = pairId.toLowerCase();
        return filters.stream().anyMatch(lower::contains);
    }

    private Metrics run(String pairId) throws IOException {
        BenchmarkFixture fixture = BenchmarkTestHelper.loadFixture(pairId);

        Table sourceTable = BenchmarkTestHelper.loadTable(
                fixture.source.file, "source-" + fixture.pair_id, fixture.source.key_columns);
        Table targetTable = BenchmarkTestHelper.loadTable(
                fixture.target.file, "target-" + fixture.pair_id, fixture.target.key_columns);

        System.out.println();
        System.out.println("=== " + pairId + " ===");
        System.out.println("  source: " + sourceTable.numRows() + " rows x "
                + sourceTable.numColumns() + " cols,  keys=" + fixture.source.key_columns);
        System.out.println("  target: " + targetTable.numRows() + " rows x "
                + targetTable.numColumns() + " cols,  keys=" + fixture.target.key_columns);

        Path gtCsvPath = Paths.get("").resolve(fixture.ground_truth.file);
        int numSrcGtCols = fixture.ground_truth.source_key_columns.size();
        Map<String, List<String>> gtMap =
                BenchmarkTestHelper.loadGroundTruth(gtCsvPath, numSrcGtCols);

        List<String> srcKeyCols = fixture.source.key_columns;
        List<String> tgtKeyCols = fixture.target.key_columns;

        JoinResult result = autoJoin.join(sourceTable, targetTable);
        assertNotNull(result, pairId + ": result is null");

        if (result.isEmpty()) {
            System.out.println("  result: EMPTY");
            return Metrics.empty(pairId, !gtMap.isEmpty(), gtMap.size());
        }

        if (gtMap.isEmpty()) {
            System.out.println("  result: " + result.size() + " joined pairs (no ground truth)");
            return Metrics.noGroundTruth(pairId, result.size());
        }

        boolean forward = BenchmarkTestHelper.isForwardDirection(result, srcKeyCols);

        int tp = 0;
        for (Row[] pair : result.getJoinedPairs()) {
            Row srcRow = forward ? pair[0] : pair[1];
            Row tgtRow = forward ? pair[1] : pair[0];

            String srcFp = BenchmarkTestHelper.positionalFingerprint(srcRow, srcKeyCols, "|");
            String tgtFp = BenchmarkTestHelper.positionalFingerprint(tgtRow, tgtKeyCols, " | ");
            List<String> expected = gtMap.get(srcFp);
            if (expected != null && tgtFp.equals(String.join(" | ", expected))) {
                tp++;
            }
        }

        int gtPairs = gtMap.size();
        double precision = (double) tp / result.size();
        double recall = gtPairs == 0 ? 0.0 : (double) tp / gtPairs;
        String dirLabel = forward ? "source → target" : "target → source";

        System.out.printf("  direction=%s  precision=%.2f  recall=%.2f  tp=%d  joined=%d  gt=%d%n",
                dirLabel, precision, recall, tp, result.size(), gtPairs);

        if (tp < gtPairs) {
            System.out.println("  --- MISMATCH DIAGNOSTICS (tp=" + tp + "/" + gtPairs + ") ---");
            BenchmarkTestHelper.printMismatchDiagnostics(result, fixture, gtMap);
        }
        if (tp == 0) {
            System.out.println("  WARNING: 0 true positives — algorithm does not match ground truth.");
        }

        return new Metrics(pairId, dirLabel, result.size(), tp, gtPairs, precision, recall, true, false);
    }

    @AfterAll
    static void printSummary() {
        if (RESULTS.isEmpty()) return;

        System.out.println();
        System.out.println("================ BENCHMARK SUMMARY (" + RESULTS.size() + " cases) ================");
        System.out.printf("%-26s %-18s %6s %6s %6s %7s %5s%n",
                "case", "direction", "prec", "recall", "tp", "joined", "gt");
        System.out.println("--------------------------------------------------------------------------------");

        List<Metrics> sorted = new ArrayList<>(RESULTS);
        sorted.sort((a, b) -> Double.compare(a.recall, b.recall));

        double precSum = 0; int precCount = 0;
        double recallSum = 0; int recallCount = 0;

        for (Metrics m : sorted) {
            String dir = m.direction == null ? "-" : m.direction;
            if (m.empty) {
                System.out.printf("%-26s %-18s %6s %6s %6s %7s %5d%n",
                        m.pairId, "EMPTY", "-", "0.00", "0", "0", m.gt);
            } else if (!m.hasGroundTruth) {
                System.out.printf("%-26s %-18s %6s %6s %6s %7d %5s%n",
                        m.pairId, dir, "-", "-", "-", m.joined, "-");
            } else {
                System.out.printf("%-26s %-18s %6.2f %6.2f %6d %7d %5d%n",
                        m.pairId, dir, m.precision, m.recall, m.tp, m.joined, m.gt);
            }

            if (m.hasGroundTruth) {
                recallSum += m.recall;
                recallCount++;
                if (!m.empty) { precSum += m.precision; precCount++; }
            }
        }

        double avgPrec = precCount == 0 ? 0 : precSum / precCount;
        double avgRecall = recallCount == 0 ? 0 : recallSum / recallCount;
        double avgF = (avgPrec + avgRecall) == 0 ? 0 : 2 * avgPrec * avgRecall / (avgPrec + avgRecall);

        System.out.println("--------------------------------------------------------------------------------");
        System.out.printf("AVG over %d gt-cases:  precision=%.3f (non-empty, n=%d)  recall=%.3f  F=%.3f%n",
                recallCount, avgPrec, precCount, avgRecall, avgF);
        System.out.println("================================================================================");
    }

    /** Per-case benchmark outcome. */
    private static final class Metrics {
        final String pairId;
        final String direction;
        final int joined;
        final int tp;
        final int gt;
        final double precision;
        final double recall;
        final boolean hasGroundTruth;
        final boolean empty;

        Metrics(String pairId, String direction, int joined, int tp, int gt,
                double precision, double recall, boolean hasGroundTruth, boolean empty) {
            this.pairId = pairId;
            this.direction = direction;
            this.joined = joined;
            this.tp = tp;
            this.gt = gt;
            this.precision = precision;
            this.recall = recall;
            this.hasGroundTruth = hasGroundTruth;
            this.empty = empty;
        }

        static Metrics empty(String pairId, boolean hasGt, int gt) {
            return new Metrics(pairId, null, 0, 0, gt, 0, 0, hasGt, true);
        }

        static Metrics noGroundTruth(String pairId, int joined) {
            return new Metrics(pairId, "?", joined, 0, 0, 0, 0, false, false);
        }
    }
}
