package com.autojoin.synthesis;

import com.autojoin.AutoJoin;
import com.autojoin.JoinResult;
import com.autojoin.model.Row;
import com.autojoin.model.Table;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end integration tests loading benchmark CSV pairs via
 * Table.fromCsv() and running the full AutoJoin pipeline.
 *
 * Each test prints diagnostics: learned transformation, column choices,
 * precision/recall vs ground truth, and sample mismatches.
 *
 * WARNING annotations signal known algorithm weaknesses to fix.
 */
class BenchmarkIntegrationTest {

    private final AutoJoin autoJoin = new AutoJoin();

    private void run(String pairId) throws IOException {
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
            return;
        }

        if (gtMap.isEmpty()) {
            System.out.println("  result: " + result.size() + " joined pairs (no ground truth)");
            return;
        }

        boolean forward = BenchmarkTestHelper.isForwardDirection(result, srcKeyCols);

        int tp = 0;
        for (Row[] pair : result.getJoinedPairs()) {
            Row srcRow = forward ? pair[0] : pair[1];
            Row tgtRow = forward ? pair[1] : pair[0];

            String srcFp = srcKeyCols.stream().map(srcRow::get).collect(Collectors.joining("|"));
            String tgtFp = tgtKeyCols.stream().map(tgtRow::get).collect(Collectors.joining(" | "));
            List<String> expected = gtMap.get(srcFp);
            if (expected != null && tgtFp.equals(String.join(" | ", expected))) {
                tp++;
            }
        }

        int gtPairs = gtMap.size();
        double precision = gtPairs == 0 ? 0.0 : (double) tp / result.size();
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
    }

    @Test
    void vegetables() throws IOException {
        run("vegetables");
    }

    @Test
    void fruits1() throws IOException {
        run("fruits 1");
    }

    @Test
    void fruits2() throws IOException {
        run("fruits 2");
    }
}
