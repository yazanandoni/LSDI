package com.autojoin.synthesis;

import com.autojoin.JoinResult;
import com.autojoin.model.Row;
import com.autojoin.model.Table;
import com.google.gson.Gson;

import java.io.FileReader;
import java.io.IOException;
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
        try (FileReader reader = new FileReader(fixturePath.toFile())) {
            return GSON.fromJson(reader, BenchmarkFixture.class);
        }
    }

    public static Table loadTable(String relativePath, String name, List<String> keyColumns)
            throws IOException {
        Path csvPath = DATA_DIR.resolve(relativePath);
        try (FileReader reader = new FileReader(csvPath.toFile())) {
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
            String tgtFp = tgtKeyCols.stream().map(tgtRow::get).collect(Collectors.joining(" | "));
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