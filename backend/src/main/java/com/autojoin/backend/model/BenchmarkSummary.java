package com.autojoin.backend.model;

import java.util.List;

public record BenchmarkSummary(
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
        List<Mismatch> mismatches,
        long indexingTimeMs,
        long learningTimeMs,
        long joinTimeMs,
        long fuzzyTimeMs,
        String method,
        boolean timedOut
) {
    public BenchmarkSummary {
        resultId = resultId != null ? resultId : "";
    }
}
