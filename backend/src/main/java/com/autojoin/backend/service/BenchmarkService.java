package com.autojoin.backend.service;

import com.autojoin.AutoJoin;
import com.autojoin.JoinResult;
import com.autojoin.backend.model.BenchmarkDescriptor;
import com.autojoin.backend.model.BenchmarkSummary;
import com.autojoin.backend.model.Mismatch;
import com.autojoin.model.Row;
import com.autojoin.model.Table;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class BenchmarkService {
    private final Path dataRoot;
    private final Path fixtureDir;
    private final BenchmarkFixtureLoader fixtureLoader;
    private final AutoJoin autoJoin = new AutoJoin();

    public record BenchmarkRunOutcome(BenchmarkSummary summary, String csv) {}

    public BenchmarkService(@Value("${app.data-root:}") String dataRootStr) {
        String root = dataRootStr;
        if (root.isEmpty()) {
            root = Files.exists(Paths.get("autojoin/data")) ? "autojoin" : ".";
        }
        this.dataRoot = Paths.get(root);
        this.fixtureDir = this.dataRoot.resolve("data/fixtures/web-benchmark");
        this.fixtureLoader = new BenchmarkFixtureLoader(fixtureDir);
    }

    @PostConstruct
    public void init() {
        System.out.println("BenchmarkService: dataRoot=" + dataRoot.toAbsolutePath()
                + ", has data dir=" + Files.exists(fixtureDir));
    }

    public List<BenchmarkDescriptor> listBenchmarks() throws IOException {
        if (!Files.exists(fixtureDir)) return List.of();
        List<BenchmarkDescriptor> benchmarks = new ArrayList<>();
        try (var paths = Files.list(fixtureDir)) {
            for (Path fixturePath : paths.toList()) {
                if (!Files.isDirectory(fixturePath)) continue;
                String pairId = fixturePath.getFileName().toString();
                if (pairId.startsWith("_")) continue;
                BenchmarkFixture fixture = fixtureLoader.loadFixture(pairId);
                Table sourceTable = loadTable(fixture.source.file, fixture.source.key_columns);
                Table targetTable = loadTable(fixture.target.file, fixture.target.key_columns);
                benchmarks.add(new BenchmarkDescriptor(pairId,
                        sourceTable.numRows(), sourceTable.numColumns(),
                        targetTable.numRows(), targetTable.numColumns(),
                        fixture.source.key_columns, fixture.target.key_columns));
            }
        }
        return benchmarks;
    }

    public BenchmarkRunOutcome runBenchmark(String pairId) throws IOException {
        long start = System.currentTimeMillis();
        BenchmarkFixture fixture = fixtureLoader.loadFixture(pairId);
        Table sourceTable = loadTable(fixture.source.file, fixture.source.key_columns);
        Table targetTable = loadTable(fixture.target.file, fixture.target.key_columns);

        Path gtCsvPath = resolvePath(fixture.ground_truth.file);
        int numSrcGtCols = fixture.ground_truth.source_key_columns.size();
        Map<String, List<String>> gtMap = loadGroundTruth(gtCsvPath, numSrcGtCols);

        JoinResult result = autoJoin.join(sourceTable, targetTable);
        long elapsed = System.currentTimeMillis() - start;

        String csv = buildResultCsv(result);
        if (result == null || result.isEmpty()) {
            return new BenchmarkRunOutcome(
                new BenchmarkSummary(pairId, "unknown", 0, 0, gtMap.size(), 0.0, 0.0, elapsed, null, List.of()),
                csv);
        }

        List<String> srcKeyCols = fixture.source.key_columns;
        List<String> tgtKeyCols = fixture.target.key_columns;
        boolean forward = isForwardDirection(result, srcKeyCols);

        int tp = 0;
        List<Mismatch> mismatches = new ArrayList<>();
        for (Row[] pair : result.getJoinedPairs()) {
            Row srcRow = forward ? pair[0] : pair[1];
            Row tgtRow = forward ? pair[1] : pair[0];
            String srcFp = srcKeyCols.stream().map(srcRow::get).collect(Collectors.joining("|"));
            String tgtFp = tgtKeyCols.stream().map(tgtRow::get).collect(Collectors.joining(" | "));
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
            csv);
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