package com.autojoin.scalability;

import com.autojoin.AutoJoin;
import com.autojoin.JoinResult;
import com.autojoin.model.Table;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;

/**
 * Paper §6.4 scalability timing (Figure 8) on DBLP-derived tables.
 *
 * <p>This class is intentionally NOT named {@code *Test}, so the normal surefire
 * run ignores it — it needs multi-GB data and a large heap. Generate the data
 * with {@code scripts/dblp_to_csv.py} (or the {@code scripts/run_scalability.sh}
 * orchestrator), then invoke it explicitly:
 *
 * <pre>
 *   mvn -Dtest=ScalabilityBenchmark -DargLine=-Xmx8g \
 *       -Ddblp.dir=/path/to/data "-Ddblp.n=100,1000,10000,100000" test
 * </pre>
 *
 * <p>Heap must be set via {@code -DargLine} (surefire forks the test JVM;
 * {@code MAVEN_OPTS} only sizes Maven itself). Add {@code -Dautojoin.debug=true}
 * for the per-stage (index / learn / equi-join) breakdown on stderr.
 *
 * <p>If {@code -Ddblp.dir} is unset or missing the test skips (assumeTrue), so an
 * accidental invocation is a no-op rather than a failure.
 */
class ScalabilityBenchmark {

    @Test
    void timeJoinAcrossSizes() throws IOException {
        String dirProp = System.getProperty("dblp.dir", "");
        Assumptions.assumeTrue(!dirProp.isBlank(),
                "set -Ddblp.dir to the directory holding dblp_<N>/ (see scripts/run_scalability.sh)");
        Path base = Paths.get(dirProp);
        Assumptions.assumeTrue(Files.isDirectory(base), "dblp.dir does not exist: " + base);

        int[] sizes = parseSizes(System.getProperty("dblp.n", "100,1000,10000,100000"));
        int repeats = Integer.getInteger("dblp.repeats", 3);

        System.out.printf("%n%-10s %-10s %-11s %s%n", "N", "joined", "median_ms", "runs_ms");
        System.out.println("-".repeat(60));
        for (int n : sizes) {
            Path dir = base.resolve(String.valueOf(n));
            if (!Files.isDirectory(dir)) {
                System.out.printf("%-10d (missing %s — skipped)%n", n, dir);
                continue;
            }
            // Source keys = the three fields, so BOTH join directions are exercised
            // (as the real tool does); target key = the concatenated column.
            Table src = load(dir.resolve("source.csv"), "src", List.of("authors", "title", "year"));
            Table tgt = load(dir.resolve("target.csv"), "tgt", List.of("record"));

            long[] times = new long[repeats];
            int joined = 0;
            for (int r = 0; r < repeats; r++) {
                long t0 = System.nanoTime();
                JoinResult res = new AutoJoin().join(src, tgt);
                times[r] = (System.nanoTime() - t0) / 1_000_000;
                joined = res.size();
            }
            Arrays.sort(times);
            System.out.printf("%-10d %-10d %-11d %s%n",
                    n, joined, times[times.length / 2], Arrays.toString(times));
        }
    }

    private static Table load(Path csv, String name, List<String> keys) throws IOException {
        try (Reader r = Files.newBufferedReader(csv, StandardCharsets.UTF_8)) {
            return Table.fromCsv(name, r, keys);
        }
    }

    private static int[] parseSizes(String s) {
        String[] parts = s.split(",");
        int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++) out[i] = Integer.parseInt(parts[i].trim());
        return out;
    }
}
