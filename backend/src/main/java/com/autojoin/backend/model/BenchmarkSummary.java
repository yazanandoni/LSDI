package com.autojoin.backend.model;

import java.util.List;

public record BenchmarkSummary(
        String pairId,
        String direction,
        int truePositives,
        int joinedPairs,
        int groundTruthPairs,
        double precision,
        double recall,
        String transformation,
        List<Mismatch> mismatches
) {
}
