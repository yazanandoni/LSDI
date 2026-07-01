package com.autojoin.backend.service;

import com.autojoin.AutoJoin;
import com.autojoin.JoinResult;
import com.autojoin.backend.model.BenchmarkDescriptor;
import com.autojoin.backend.model.BenchmarkSummary;
import com.autojoin.backend.model.Mismatch;
import com.autojoin.baselines.FuzzyJoinColumn;
import com.autojoin.baselines.FuzzyJoinOracle;
import com.autojoin.baselines.JoinMethod;
import com.autojoin.baselines.SubstringMatching;
import com.autojoin.model.Row;
import com.autojoin.model.Table;
import com.autojoin.trace.AlgorithmTrace;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
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
                    int[] srcInfo = countCsvRowsAndColumns(resolvePath(fixture.source.file));
                    int[] tgtInfo = countCsvRowsAndColumns(resolvePath(fixture.target.file));
                    benchmarks.add(new BenchmarkDescriptor(pairId,
                            srcInfo[0], srcInfo[1],
                            tgtInfo[0], tgtInfo[1],
                            fixture.source.key_columns, fixture.target.key_columns));
                }
            }
        }
        return benchmarks;
    }

    public BenchmarkRunOutcome runBenchmark(String pairId, String method) throws IOException {
        long start = System.currentTimeMillis();
        BenchmarkFixture fixture = loadFixture(pairId);
        Table sourceTable = loadTable(fixture.source.file, fixture.source.key_columns);
        Table targetTable = loadTable(fixture.target.file, fixture.target.key_columns);
        List<String> srcKeyCols = fixture.source.key_columns;
        List<String> tgtKeyCols = fixture.target.key_columns;

        Path gtCsvPath = resolvePath(fixture.ground_truth.file);
        int numSrcGtCols = fixture.ground_truth.source_key_columns.size();
        Map<String, List<String>> gtMap = loadGroundTruth(gtCsvPath, numSrcGtCols);

        AlgorithmTrace trace = null;
        String transformDesc = null;
        List<Row[]> joinedPairs;
        String dirLabel;

        if (method == null || method.equals("AJ")) {
            JoinResult result = autoJoin.join(sourceTable, targetTable);
            trace = result.getTrace();
            transformDesc = result.getTransformationDescription();
            joinedPairs = result.getJoinedPairs();
            dirLabel = !result.isEmpty() && isForwardDirection(result, srcKeyCols)
                    ? "source -> target" : "target -> source";
        } else {
            JoinMethod baseline = getMethod(method);
            JoinMethod.JoinInput input = new JoinMethod.JoinInput(
                    sourceTable, targetTable, srcKeyCols, tgtKeyCols);
            joinedPairs = baseline.join(input);
            transformDesc = method;
            dirLabel = sourceTable.getName() + " -> " + targetTable.getName();
        }

        long elapsed = System.currentTimeMillis() - start;
        long idxMs = trace != null ? trace.getDiscoveryMs() : 0;
        long lrnMs = trace != null ? trace.getLearningMs() : 0;
        long jnMs  = trace != null ? trace.getJoinMs() : 0;
        long fzMs  = trace != null ? trace.getFuzzyMs() : 0;

        String csv = buildResultCsv(joinedPairs);
        if (joinedPairs == null || joinedPairs.isEmpty()) {
            return new BenchmarkRunOutcome(
                new BenchmarkSummary(pairId, "unknown", 0, 0, gtMap.size(), 0.0, 0.0, elapsed, null, List.of(),
                        idxMs, lrnMs, jnMs, fzMs, method != null ? method : "AJ"),
                csv, trace);
        }

        int tp = 0;
        List<Mismatch> mismatches = new ArrayList<>();
        boolean forward = !method.equals("AJ") || isForwardDirection(
                JoinResult.of(joinedPairs, transformDesc), srcKeyCols);
        for (Row[] pair : joinedPairs) {
            Row srcRow = forward ? pair[0] : pair[1];
            Row tgtRow = forward ? pair[1] : pair[0];
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
        double precision = gtPairs == 0 ? 0.0 : (double) tp / joinedPairs.size();
        double recall = gtPairs == 0 ? 0.0 : (double) tp / gtPairs;
        String methodLabel = method != null ? method : "AJ";

        return new BenchmarkRunOutcome(
            new BenchmarkSummary(pairId, dirLabel, tp, joinedPairs.size(), gtPairs, precision, recall, elapsed,
                    transformDesc, mismatches,
                    idxMs, lrnMs, jnMs, fzMs, methodLabel),
            csv, trace);
    }

    public List<BenchmarkRunOutcome> runBenchmarks(List<String> pairIds, String method) throws IOException {
        List<BenchmarkRunOutcome> outcomes = new ArrayList<>();
        for (String pairId : pairIds) {
            outcomes.add(runBenchmark(pairId, method));
        }
        return outcomes;
    }

    public List<BenchmarkRunOutcome> runBenchmarks(List<String> pairIds, List<String> methods) throws IOException {
        List<BenchmarkRunOutcome> outcomes = new ArrayList<>();
        for (int i = 0; i < pairIds.size(); i++) {
            String method = (methods != null && i < methods.size()) ? methods.get(i) : "AJ";
            outcomes.add(runBenchmark(pairIds.get(i), method));
        }
        return outcomes;
    }

    private JoinMethod getMethod(String name) {
        return switch (name) {
            case "SM" -> new SubstringMatching();
            case "FJ-C" -> new FuzzyJoinColumn();
            case "FJ-O" -> new FuzzyJoinOracle();
            default -> throw new IllegalArgumentException("Unknown method: " + name);
        };
    }

    private String buildResultCsv(List<Row[]> joinedPairs) {
        if (joinedPairs == null || joinedPairs.isEmpty()) return "";
        Row firstSource = joinedPairs.get(0)[0];
        Row firstTarget = joinedPairs.get(0)[1];

        StringBuilder sb = new StringBuilder();
        for (String col : firstSource.getColumnNames()) {
            sb.append(escapeCsv(col)).append(",");
        }
        for (String col : firstTarget.getColumnNames()) {
            sb.append(escapeCsv(col)).append(",");
        }
        sb.setLength(sb.length() - 1);
        sb.append("\n");

        for (Row[] pair : joinedPairs) {
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

    private Path resolvePath(String relativePath) {
        return dataRoot.resolve(relativePath);
    }

    /**
     * Lightweight CSV row/column counter — reads the header line for column
     * count and counts data rows, without materialising the full table.
     * Returns int[]{rows, columns}.
     */
    private int[] countCsvRowsAndColumns(Path csvPath) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(csvPath.toFile(), "r")) {
            // Read first 2KB for the header line
            byte[] head = new byte[2048];
            int headLen = raf.read(head);
            if (headLen <= 0) return new int[]{0, 0};
            String headerStr = new String(head, 0, headLen, StandardCharsets.UTF_8);
            int headerEnd = headerStr.indexOf('\n');
            if (headerEnd < 0) return new int[]{0, 0};
            int cols = headerStr.substring(0, headerEnd).split(",", -1).length;

            // Count newlines by scanning raw bytes (no String allocation)
            raf.seek(0);
            byte[] buf = new byte[8192];
            int read;
            long newlines = 0;
            while ((read = raf.read(buf)) != -1) {
                for (int i = 0; i < read; i++) {
                    if (buf[i] == '\n') newlines++;
                }
            }
            return new int[]{(int) (newlines - 1), cols};
        }
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