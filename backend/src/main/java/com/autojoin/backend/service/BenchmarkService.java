package com.autojoin.backend.service;

import com.autojoin.AutoJoin;
import com.autojoin.JoinResult;
import com.autojoin.backend.model.BenchmarkDescriptor;
import com.autojoin.backend.model.BenchmarkSummary;
import com.autojoin.backend.model.Mismatch;
import com.autojoin.baselines.DynamicQGram;
import com.autojoin.baselines.FuzzyJoinColumn;
import com.autojoin.baselines.FuzzyJoinFullRow;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Service
public class BenchmarkService {
    private final Path dataRoot;
    private final List<Path> fixtureRoots;
    private final List<BenchmarkFixtureLoader> fixtureLoaders;
    private final AutoJoin autoJoin = new AutoJoin();
    /** AJ-E (paper sec. 6.2): Auto-Join with equality join only, no sec. 5 fuzzy step. */
    private final AutoJoin autoJoinEquiOnly = new AutoJoin(false);

    /**
     * Paper §6.4: "Some existing methods are very slow on large data sets so we
     * set a timeout at 2 hours." Same mechanism here — SM/FJ-C/FJ-O actually run
     * and are cut off when the budget expires (the timeout IS the "does not
     * scale" result: Figure 8 has SM and FJ-O timing out at 10K rows and FJ-C at
     * 100K). 2h is impractical for an interactive UI, so the budget defaults to
     * 300s and is configurable via APP_BASELINE_TIMEOUT_SECONDS. AJ runs
     * without a budget, as in the paper, where it finishes at every size.
     */
    private final int baselineTimeoutSeconds;

    /** Runs baseline joins so they can be cancelled; the baselines poll the
     *  thread's interrupt flag in their hot loops and abort when cut off. */
    private final ExecutorService baselineExecutor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "baseline-join");
        t.setDaemon(true);
        return t;
    });

    public record BenchmarkRunOutcome(BenchmarkSummary summary, String csv, AlgorithmTrace trace) {}

    public int getBaselineTimeoutSeconds() {
        return baselineTimeoutSeconds;
    }

    public BenchmarkService(@Value("${app.data-root:}") String dataRootStr,
                            @Value("${app.baseline-timeout-seconds:300}") int baselineTimeoutSeconds) {
        this.baselineTimeoutSeconds = baselineTimeoutSeconds;
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
        // Warm the CSV row-count cache so the first /api/benchmarks call is
        // instant — counting rows means scanning every fixture CSV once, and
        // the large DBLP files make a cold scan take seconds.
        Thread warmer = new Thread(() -> {
            try {
                long t0 = System.currentTimeMillis();
                int n = listBenchmarks().size();
                System.out.println("BenchmarkService: warmed row-count cache for " + n
                        + " fixtures in " + (System.currentTimeMillis() - t0) + "ms");
            } catch (Exception e) {
                System.err.println("BenchmarkService: cache warm-up failed: " + e.getMessage());
            }
        }, "benchmark-cache-warmer");
        warmer.setDaemon(true);
        warmer.start();
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
                    try {
                        int[] srcInfo = countCsvRowsAndColumnsCached(resolvePath(fixture.source.file));
                        int[] tgtInfo = countCsvRowsAndColumnsCached(resolvePath(fixture.target.file));
                        benchmarks.add(new BenchmarkDescriptor(pairId,
                                srcInfo[0], srcInfo[1],
                                tgtInfo[0], tgtInfo[1],
                                fixture.source.key_columns, fixture.target.key_columns));
                    } catch (IOException e) {
                        System.err.println("Skipping fixture " + pairId + ": " + e.getMessage());
                    }
                }
            }
        }
        return benchmarks;
    }

    public BenchmarkRunOutcome runBenchmark(String pairId, String method) throws IOException {
        // Paper sec. 6.2: FJ-O is an ORACLE — it picks the configuration with the
        // highest average F across ALL web cases, using the ground truth. Resolve
        // (and cache) that global config before the timer starts, so the one-time
        // oracle search does not distort this run's measured time. DBLP runs keep
        // the fixed-config standalone join: there FJ-O is only timed (sec. 6.4)
        // and the config choice does not change the grid cost.
        FuzzyJoinOracle.Config fjoConfig = null;
        if ("FJ-O".equals(method) && !pairId.startsWith("dblp-")) {
            fjoConfig = oracleConfig();
        }

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
        String methodLabel = method != null ? method : "AJ";
        boolean timedOut = false;
        // AJ and AJ-E are the paper's own method (AJ-E = equality join only, no
        // sec. 5 fuzzy step) — both run untimed with a full trace, like AJ always has.
        boolean isAutoJoin = methodLabel.equals("AJ") || methodLabel.equals("AJ-E");

        if (isAutoJoin) {
            AutoJoin aj = methodLabel.equals("AJ-E") ? autoJoinEquiOnly : autoJoin;
            JoinResult result = aj.join(sourceTable, targetTable);
            trace = result.getTrace();
            transformDesc = result.getTransformationDescription();
            joinedPairs = result.getJoinedPairs();
            dirLabel = methodLabel + ": " + (!result.isEmpty() && isForwardDirection(result, srcKeyCols)
                    ? "source -> target" : "target -> source");
        } else {
            JoinMethod baseline = fjoConfig != null ? oracleAt(fjoConfig) : getMethod(method);
            JoinMethod.JoinInput input = new JoinMethod.JoinInput(
                    sourceTable, targetTable, srcKeyCols, tgtKeyCols);
            Future<List<Row[]>> run = baselineExecutor.submit(() -> baseline.join(input));
            try {
                joinedPairs = run.get(baselineTimeoutSeconds, TimeUnit.SECONDS);
                transformDesc = method;
            } catch (TimeoutException e) {
                run.cancel(true);
                joinedPairs = List.of();
                timedOut = true;
                transformDesc = method + " did not finish within " + baselineTimeoutSeconds
                        + "s and was cut off (paper sec. 6.4 applies a 2h timeout the same way;"
                        + " these methods grow super-linearly with table size)";
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("baseline run interrupted", e);
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof RuntimeException re) throw re;
                throw new IOException("baseline " + method + " failed", cause);
            }
            dirLabel = methodLabel + (timedOut ? ": timed out" : ": source -> target");
        }

        long elapsed = System.currentTimeMillis() - start;
        long idxMs = trace != null ? trace.getDiscoveryMs() : 0;
        long lrnMs = trace != null ? trace.getLearningMs() : 0;
        long jnMs  = trace != null ? trace.getJoinMs() : 0;
        long fzMs  = trace != null ? trace.getFuzzyMs() : 0;

        String csv = buildResultCsv(joinedPairs);
        if (joinedPairs == null || joinedPairs.isEmpty()) {
            return new BenchmarkRunOutcome(
                new BenchmarkSummary(pairId, timedOut ? dirLabel : methodLabel + ": empty",
                        0, 0, gtMap.size(), 0.0, 0.0, elapsed, transformDesc, List.of(),
                        idxMs, lrnMs, jnMs, fzMs, methodLabel, timedOut),
                csv, trace);
        }

        // Set-based scoring per the paper (sec. 6.1): a produced pair is its
        // (srcKey, tgtKey) fingerprint, deduped so a method emitting the same
        // pair twice cannot double-count a true positive (recall > 1) or a
        // mismatch. Mirrors the MethodComparisonTest scorer.
        int tp = 0;
        List<Mismatch> mismatches = new ArrayList<>();
        Set<String> produced = new LinkedHashSet<>();
        boolean forward = !isAutoJoin || isForwardDirection(
                JoinResult.of(joinedPairs, transformDesc), srcKeyCols);
        for (Row[] pair : joinedPairs) {
            Row srcRow = forward ? pair[0] : pair[1];
            Row tgtRow = forward ? pair[1] : pair[0];
            String srcFp = positionalFingerprint(srcRow, srcKeyCols, "|");
            String tgtFp = positionalFingerprint(tgtRow, tgtKeyCols, " | ");
            if (!produced.add(srcFp + "\t\t" + tgtFp)) continue; // duplicate pair
            List<String> expected = gtMap.get(srcFp);
            if (expected != null && tgtFp.equals(String.join(" | ", expected))) {
                tp++;
            } else {
                mismatches.add(new Mismatch(srcFp, expected, tgtFp));
            }
        }

        int gtPairs = gtMap.size();
        double precision = gtPairs == 0 ? 0.0 : (double) tp / produced.size();
        double recall = gtPairs == 0 ? 0.0 : (double) tp / gtPairs;

        return new BenchmarkRunOutcome(
            new BenchmarkSummary(pairId, dirLabel, tp, produced.size(), gtPairs, precision, recall, elapsed,
                    transformDesc, mismatches,
                    idxMs, lrnMs, jnMs, fzMs, methodLabel, false),
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

    /** Cached FJ-O oracle config; computed once from all web-benchmark cases. */
    private volatile FuzzyJoinOracle.Config fjoOracleConfig;

    /**
     * The paper's FJ-O oracle rule (sec. 6.2): evaluate all 520 configurations
     * on every web-benchmark case and "report the best configuration that has
     * the highest average F-score across all cases" — chosen with the ground
     * truth, which is exactly why FJ-O is an upper bound and not a usable
     * method. Runs once (a minute or two) and is cached for the process life.
     */
    private synchronized FuzzyJoinOracle.Config oracleConfig() throws IOException {
        if (fjoOracleConfig != null) return fjoOracleConfig;
        long t0 = System.currentTimeMillis();

        List<FuzzyJoinOracle.CaseEval> evals = new ArrayList<>();
        List<List<String>> srcKeys = new ArrayList<>();
        List<List<String>> tgtKeys = new ArrayList<>();
        List<Map<String, List<String>>> gts = new ArrayList<>();
        for (BenchmarkDescriptor d : listBenchmarks()) {
            if (d.pairId().startsWith("dblp-")) continue;
            BenchmarkFixture fx = loadFixture(d.pairId());
            Map<String, List<String>> gt = loadGroundTruth(
                    resolvePath(fx.ground_truth.file), fx.ground_truth.source_key_columns.size());
            if (gt.isEmpty()) continue;
            Table src = loadTable(fx.source.file, fx.source.key_columns);
            Table tgt = loadTable(fx.target.file, fx.target.key_columns);
            evals.add(FuzzyJoinOracle.prepare(new JoinMethod.JoinInput(
                    src, tgt, fx.source.key_columns, fx.target.key_columns)));
            srcKeys.add(fx.source.key_columns);
            tgtKeys.add(fx.target.key_columns);
            gts.add(gt);
        }

        FuzzyJoinOracle.Config best = null;
        double bestF = -1;
        for (FuzzyJoinOracle.Config cfg : FuzzyJoinOracle.grid()) {
            double sumF = 0;
            for (int i = 0; i < evals.size(); i++) {
                sumF += fScore(evals.get(i).pairsAt(cfg), srcKeys.get(i), tgtKeys.get(i), gts.get(i));
            }
            double avgF = evals.isEmpty() ? 0 : sumF / evals.size();
            if (avgF > bestF) {
                bestF = avgF;
                best = cfg;
            }
        }
        fjoOracleConfig = best != null
                ? best
                : new FuzzyJoinOracle.Config(FuzzyJoinOracle.Tok.WORD, FuzzyJoinOracle.Dist.JACCARD, 0.5);
        System.out.println("FJ-O oracle config: " + fjoOracleConfig.label()
                + String.format(java.util.Locale.US, " (avg F %.3f over %d web cases, chosen in %dms)",
                        bestF, evals.size(), System.currentTimeMillis() - t0));
        return fjoOracleConfig;
    }

    /** FJ-O pinned to the oracle-chosen configuration (still pays the full grid). */
    private static JoinMethod oracleAt(FuzzyJoinOracle.Config cfg) {
        return new JoinMethod() {
            @Override public String name() { return "FJ-O"; }
            @Override public List<Row[]> join(JoinInput in) {
                return FuzzyJoinOracle.prepare(in).pairsAt(cfg);
            }
        };
    }

    /** Set-based F-score of produced pairs vs ground truth (paper sec. 6.1). */
    private double fScore(List<Row[]> pairs, List<String> srcKeyCols,
                          List<String> tgtKeyCols, Map<String, List<String>> gtMap) {
        Set<String> produced = new LinkedHashSet<>();
        int tp = 0;
        for (Row[] pair : pairs) {
            String srcFp = positionalFingerprint(pair[0], srcKeyCols, "|");
            String tgtFp = positionalFingerprint(pair[1], tgtKeyCols, " | ");
            if (!produced.add(srcFp + "\t\t" + tgtFp)) continue;
            List<String> expected = gtMap.get(srcFp);
            if (expected != null && tgtFp.equals(String.join(" | ", expected))) tp++;
        }
        if (produced.isEmpty() || gtMap.isEmpty()) return 0;
        double p = (double) tp / produced.size();
        double r = (double) tp / gtMap.size();
        return (p + r) == 0 ? 0 : 2 * p * r / (p + r);
    }

    private JoinMethod getMethod(String name) {
        return switch (name) {
            case "SM" -> new SubstringMatching();
            case "FJ-C" -> new FuzzyJoinColumn();
            case "FJ-FR" -> new FuzzyJoinFullRow();
            case "FJ-O" -> new FuzzyJoinOracle();
            case "DQ-P" -> new DynamicQGram(true);
            case "DQ-R" -> new DynamicQGram(false);
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

    /** Cached row/column counts, validated by file size + mtime so the counts
     *  survive fixture regeneration but big CSVs are only ever scanned once. */
    private record CsvStats(long size, long mtime, int rows, int cols) {}
    private final Map<Path, CsvStats> csvStatsCache = new ConcurrentHashMap<>();

    private int[] countCsvRowsAndColumnsCached(Path csvPath) throws IOException {
        Path abs = csvPath.toAbsolutePath().normalize();
        long size = Files.size(abs);
        long mtime = Files.getLastModifiedTime(abs).toMillis();
        CsvStats cached = csvStatsCache.get(abs);
        if (cached == null || cached.size() != size || cached.mtime() != mtime) {
            int[] counted = countCsvRowsAndColumns(abs);
            cached = new CsvStats(size, mtime, counted[0], counted[1]);
            csvStatsCache.put(abs, cached);
        }
        return new int[]{cached.rows(), cached.cols()};
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