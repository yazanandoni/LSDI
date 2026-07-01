package com.autojoin.backend.service;

import com.autojoin.AutoJoin;
import com.autojoin.JoinResult;
import com.autojoin.backend.model.BenchmarkDescriptor;
import com.autojoin.backend.model.BenchmarkSummary;
import com.autojoin.backend.model.Mismatch;
import com.autojoin.model.Row;
import com.autojoin.model.Table;
import com.autojoin.trace.AlgorithmTrace;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class BenchmarkService {
    private final Path dataRoot;
    private final List<Path> fixtureRoots;
    private final List<BenchmarkFixtureLoader> fixtureLoaders;
    private final AutoJoin autoJoin = new AutoJoin();

    public record BenchmarkRunOutcome(BenchmarkSummary summary, String csv, AlgorithmTrace trace) {}

    public BenchmarkService(@Value("${app.data-root:}") String dataRootStr) {
        String root = dataRootStr;
        if (root.isEmpty()) {
            root = Files.exists(Paths.get("autojoin/data")) ? "autojoin" : ".";
        }
        this.dataRoot = Paths.get(root);
        this.fixtureRoots = List.of(
                this.dataRoot.resolve("data/fixtures/web-benchmark"),
                this.dataRoot.resolve("data/fixtures/dblp-scalability"));
        this.fixtureLoaders = fixtureRoots.stream()
                .map(BenchmarkFixtureLoader::new)
                .toList();
    }

    @PostConstruct
    public void init() {
        System.out.println("BenchmarkService: dataRoot=" + dataRoot.toAbsolutePath());
        for (Path root : fixtureRoots) {
            System.out.println("  fixture root: " + root + " exists=" + Files.exists(root));
        }
    }

    public List<BenchmarkDescriptor> listBenchmarks() throws IOException {
        Set<String> seen = new LinkedHashSet<>();
        List<BenchmarkDescriptor> benchmarks = new ArrayList<>();
        for (int i = 0; i < fixtureRoots.size(); i++) {
            Path root = fixtureRoots.get(i);
            if (!Files.exists(root)) continue;
            BenchmarkFixtureLoader loader = fixtureLoaders.get(i);
            try (var paths = Files.list(root)) {
                for (Path fixturePath : paths.toList()) {
                    if (!Files.isDirectory(fixturePath)) continue;
                    String pairId = fixturePath.getFileName().toString();
                    if (pairId.startsWith("_") || !seen.add(pairId)) continue;
                    BenchmarkFixture fixture = loader.loadFixture(pairId);
                    Table sourceTable = loadTable(fixture.source.file, fixture.source.key_columns);
                    Table targetTable = loadTable(fixture.target.file, fixture.target.key_columns);
                    benchmarks.add(new BenchmarkDescriptor(pairId,
                            sourceTable.numRows(), sourceTable.numColumns(),
                            targetTable.numRows(), targetTable.numColumns(),
                            fixture.source.key_columns, fixture.target.key_columns));
                }
            }
        }
        return benchmarks;
    }

    public BenchmarkRunOutcome runBenchmark(String pairId) throws IOException {
        long start = System.currentTimeMillis();
        BenchmarkFixture fixture = loadFixture(pairId);
        Table sourceTable = loadTable(fixture.source.file, fixture.source.key_columns);
        Table targetTable = loadTable(fixture.target.file, fixture.target.key_columns);

        Path gtCsvPath = resolvePath(fixture.ground_truth.file);
        int numSrcGtCols = fixture.ground_truth.source_key_columns.size();
        Map<String, List<String>> gtMap = loadGroundTruth(gtCsvPath, numSrcGtCols);

        JoinResult result = autoJoin.join(sourceTable, targetTable);
        long elapsed = System.currentTimeMillis() - start;

        AlgorithmTrace trace = result.getTrace();

        String csv = buildResultCsv(result);
        if (result == null || result.isEmpty()) {
            return new BenchmarkRunOutcome(
                new BenchmarkSummary(pairId, "unknown", 0, 0, gtMap.size(), 0.0, 0.0, elapsed, null, List.of()),
                csv, trace);
        }

        List<String> srcKeyCols = fixture.source.key_columns;
        List<String> tgtKeyCols = fixture.target.key_columns;
        boolean forward = isForwardDirection(result, srcKeyCols);

        int tp = 0;
        List<Mismatch> mismatches = new ArrayList<>();
        for (Row[] pair : result.getJoinedPairs()) {
            Row srcRow = forward ? pair[0] : pair[1];
            Row tgtRow = forward ? pair[1] : pair[0];
            // Positional fingerprints: name-based lookup collapses duplicate
            // column names (chinese provinces has two "Hanzi" target columns,
            // us presidents 4 two "Vice President" source columns) onto one
            // value, falsely scoring correct joins as mismatches in the UI.
            String srcFp = positionalFingerprint(srcRow, srcKeyCols, "|");
            String tgtFp = positionalFingerprint(tgtRow, tgtKeyCols, " | ");
            List<String> expected = gtMap.get(srcFp);
            if (expected != null && tgtFp.equals(String.join(" | ", expected))) {
                tp++;
            } else {
                mismatches.add(new Mismatch(srcFp, expected, tgtFp));
            }
        }

        int gtPairs = gtMap.size();
        double precision = gtPairs == 0 ? 0.0 : (double) tp / result.size();
        double recall = gtPairs == 0 ? 0.0 : (double) tp / gtPairs;
        String dirLabel = forward ? "source -> target" : "target -> source";

        return new BenchmarkRunOutcome(
            new BenchmarkSummary(pairId, dirLabel, tp, result.size(), gtPairs, precision, recall, elapsed,
                    result.getTransformationDescription(), mismatches),
            csv, trace);
    }

    private String buildResultCsv(JoinResult result) {
        if (result == null || result.isEmpty()) return "";
        List<Row[]> pairs = result.getJoinedPairs();
        Row firstSource = pairs.get(0)[0];
        Row firstTarget = pairs.get(0)[1];

        StringBuilder sb = new StringBuilder();
        for (String col : firstSource.getColumnNames()) {
            sb.append(escapeCsv(col)).append(",");
        }
        for (String col : firstTarget.getColumnNames()) {
            sb.append(escapeCsv(col)).append(",");
        }
        sb.setLength(sb.length() - 1);
        sb.append("\n");

        for (Row[] pair : pairs) {
            for (int i = 0; i < pair[0].size(); i++) {
                sb.append(escapeCsv(pair[0].get(i))).append(",");
            }
            for (int i = 0; i < pair[1].size(); i++) {
                sb.append(escapeCsv(pair[1].get(i))).append(",");
            }
            sb.setLength(sb.length() - 1);
            sb.append("\n");
        }
        return sb.toString();
    }

    private String escapeCsv(String value) {
        if (value == null) return "";
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    public List<BenchmarkRunOutcome> runBenchmarks(List<String> pairIds) throws IOException {
        List<BenchmarkRunOutcome> outcomes = new ArrayList<>();
        for (String pairId : pairIds) {
            outcomes.add(runBenchmark(pairId));
        }
        return outcomes;
    }

    private Path resolvePath(String relativePath) {
        return dataRoot.resolve(relativePath);
    }

    /**
     * Load a fixture by pairId, searching each fixture root in order.
     * Throws FileNotFoundException if not found in any root.
     */
    private BenchmarkFixture loadFixture(String pairId) throws IOException {
        for (BenchmarkFixtureLoader loader : fixtureLoaders) {
            try {
                return loader.loadFixture(pairId);
            } catch (FileNotFoundException e) {
                // try next root
            }
        }
        throw new FileNotFoundException("Fixture not found: " + pairId
                + " (searched " + fixtureRoots + ")");
    }

    private Table loadTable(String relativePath, List<String> keyColumns) throws IOException {
        try (FileReader reader = new FileReader(resolvePath(relativePath).toFile())) {
            return Table.fromCsv("t", reader, keyColumns);
        }
    }

    private Map<String, List<String>> loadGroundTruth(Path gtCsvPath, int numSourceCols) throws IOException {
        List<String> lines = Files.readAllLines(gtCsvPath);
        if (lines.isEmpty()) return Map.of();
        Map<String, List<String>> map = new LinkedHashMap<>();
        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.isBlank()) continue;
            String[] fields = parseCsvLine(line);
            if (fields.length < numSourceCols + 1) continue;
            String srcFp = String.join("|", Arrays.copyOfRange(fields, 0, numSourceCols));
            List<String> tgtValues = List.of(Arrays.copyOfRange(fields, numSourceCols, fields.length));
            boolean hasData = tgtValues.stream().anyMatch(v -> !v.isBlank());
            if (hasData && !srcFp.isBlank()) map.put(srcFp, tgtValues);
        }
        return map;
    }

    /**
     * Build a fingerprint from a row's key columns, resolving duplicate column
     * names positionally: the k-th occurrence of a name in keyCols maps to the
     * k-th column of that name in the row. Mirrors BenchmarkTestHelper.
     */
    private static String positionalFingerprint(Row row, List<String> keyCols, String sep) {
        List<String> rowCols = row.getColumnNames();
        boolean[] used = new boolean[rowCols.size()];
        List<String> parts = new ArrayList<>(keyCols.size());
        for (String key : keyCols) {
            int idx = -1;
            for (int i = 0; i < rowCols.size(); i++) {
                if (!used[i] && rowCols.get(i).equals(key)) { idx = i; break; }
            }
            if (idx >= 0) {
                used[idx] = true;
                parts.add(row.get(idx));
            } else {
                parts.add(row.get(key)); // key not present positionally — fall back
            }
        }
        return String.join(sep, parts);
    }

    private boolean isForwardDirection(JoinResult result, List<String> srcKeyCols) {
        if (result.isEmpty()) return true;
        Row first = result.getJoinedPairs().get(0)[0];
        return srcKeyCols.stream().allMatch(c -> first.getColumnNames().contains(c));
    }

    private String[] parseCsvLine(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') inQuotes = !inQuotes;
            else if (c == ',' && !inQuotes) { fields.add(sb.toString().trim()); sb.setLength(0); }
            else sb.append(c);
        }
        fields.add(sb.toString().trim());
        return fields.toArray(new String[0]);
    }
}