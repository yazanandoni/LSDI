package com.autojoin.backend.model;

import java.util.List;

public record BenchmarkDescriptor(
        String pairId,
        int sourceRows,
        int sourceColumns,
        int targetRows,
        int targetColumns,
        List<String> sourceKeys,
        List<String> targetKeys
) {
}
