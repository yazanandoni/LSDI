package com.autojoin.backend.model;

import java.util.List;

public record BenchmarkSummaryView(
        String resultId,
        String pairId,
        String direction,
        int truePositives,
        int joinedPairs,
        int groundTruthPairs,
        double precision,
        double recall,
        long durationMs,
        String transformation,
        List<Mismatch> mismatches
) {
}
