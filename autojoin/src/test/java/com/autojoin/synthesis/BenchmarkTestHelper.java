package com.autojoin.synthesis;

import com.autojoin.JoinResult;
import com.autojoin.model.Row;
import com.autojoin.model.Table;
import com.google.gson.Gson;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

public class BenchmarkTestHelper {

    private static final Gson GSON = new Gson();
    private static final Path FIXTURE_DIR = Paths.get("data/fixtures/web-benchmark");
    private static final Path DATA_DIR = Paths.get("");

    public static BenchmarkFixture loadFixture(String pairId) throws IOException {
        Path fixturePath = FIXTURE_DIR.resolve(pairId).resolve("fixture.json");
        try (Reader reader = Files.newBufferedReader(fixturePath, StandardCharsets.UTF_8)) {
            return GSON.fromJson(reader, BenchmarkFixture.class);
        }
    }

    /** Returns every benchmark pair id (one per sub-directory of the fixture dir), sorted. */
    public static List<String> listFixtures() throws IOException {
        try (java.util.stream.Stream<Path> entries = Files.list(FIXTURE_DIR)) {
            return entries
                    .filter(Files::isDirectory)
                    .map(p -> p.getFileName().toString())
                    .sorted()
                    .collect(Collectors.toList());
        }
    }

    public static Table loadTable(String relativePath, String name, List<String> keyColumns)
            throws IOException {
        Path csvPath = DATA_DIR.resolve(relativePath);
        try (Reader reader = Files.newBufferedReader(csvPath, StandardCharsets.UTF_8)) {
            return Table.fromCsv(name, reader, keyColumns);
        }
    }

    public static Map<String, List<String>> loadGroundTruth(Path gtCsvPath, int numSourceCols)
            throws IOException {
        List<String> lines = Files.readAllLines(gtCsvPath);
        if (lines.isEmpty()) return Map.of();

        Map<String, List<String>> map = new LinkedHashMap<>();
        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.isBlank()) continue;
            String[] fields = parseCsvLine(line);
            if (fields.length < numSourceCols + 1) continue;

            String srcFp = String.join("|",
                    Arrays.copyOfRange(fields, 0, numSourceCols));
            List<String> tgtValues = List.of(
                    Arrays.copyOfRange(fields, numSourceCols, fields.length));

            boolean hasData = tgtValues.stream().anyMatch(v -> !v.isBlank());
            if (hasData && !srcFp.isBlank()) {
                map.put(srcFp, tgtValues);
            }
        }
        return map;
    }

    /**
     * Build a fingerprint from a row's key columns, resolving duplicate column
     * names positionally: the k-th occurrence of a name in {@code keyCols} maps
     * to the k-th column of that name in the row. This keeps duplicate headers
     * (e.g. a target with two "Hanzi" columns) distinct, matching the positional
     * ground-truth fingerprint. A plain {@code row.get(name)} would collapse both
     * occurrences onto a single value and never equal the ground truth.
     */
    public static String positionalFingerprint(Row row, List<String> keyCols, String sep) {
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

    public static boolean isForwardDirection(JoinResult result, List<String> srcKeyCols) {
        if (result.isEmpty()) return true;
        Row first = result.getJoinedPairs().get(0)[0];
        return srcKeyCols.stream().allMatch(c -> first.getColumnNames().contains(c));
    }

    public static void printMismatchDiagnostics(JoinResult result,
                                                 BenchmarkFixture fixture,
                                                 Map<String, List<String>> gtMap) {
        List<String> srcKeyCols = fixture.source.key_columns;
        List<String> tgtKeyCols = fixture.target.key_columns;
        boolean forward = isForwardDirection(result, srcKeyCols);

        System.out.println("  direction: " + (forward ? "source → target" : "target → source (backward)"));
        System.out.println("  transform: " + result.getTransformationDescription());

        int mismatches = 0;
        int maxPrint = 5;

        for (Row[] pair : result.getJoinedPairs()) {
            Row srcRow = forward ? pair[0] : pair[1];
            Row tgtRow = forward ? pair[1] : pair[0];

            String srcFp = srcKeyCols.stream().map(srcRow::get).collect(Collectors.joining("|"));
            String tgtFp = positionalFingerprint(tgtRow, tgtKeyCols, " | ");
            List<String> expectedTgt = gtMap.get(srcFp);

            boolean mismatch = expectedTgt == null
                    || !tgtFp.equals(String.join(" | ", expectedTgt));
            if (mismatch) {
                mismatches++;
                if (maxPrint > 0) {
                    System.out.println("  mismatch: " + srcFp);
                    System.out.println("    → joined to: " + tgtFp);
                    System.out.println("    → expected:  " + (expectedTgt == null
                            ? "(no ground truth entry)" : String.join(" | ", expectedTgt)));
                    maxPrint--;
                }
            }
        }

        if (mismatches > 0) {
            System.out.println("  (" + mismatches + " mismatches total)");
        }
    }

    private static String[] parseCsvLine(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                inQuotes = !inQuotes;
            } else if (c == ',' && !inQuotes) {
                fields.add(sb.toString().trim());
                sb.setLength(0);
            } else {
                sb.append(c);
            }
        }
        fields.add(sb.toString().trim());
        return fields.toArray(new String[0]);
    }
}