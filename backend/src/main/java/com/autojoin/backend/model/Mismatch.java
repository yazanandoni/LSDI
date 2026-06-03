package com.autojoin.backend.model;

import java.util.List;

public record Mismatch(
        String sourceFingerprint,
        List<String> expectedTarget,
        String joinedTarget
) {
}
