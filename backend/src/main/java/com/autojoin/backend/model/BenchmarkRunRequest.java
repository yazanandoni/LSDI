package com.autojoin.backend.model;

import jakarta.validation.constraints.NotBlank;

public record BenchmarkRunRequest(
        @NotBlank String pairId
) {
}
