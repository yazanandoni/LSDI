package com.autojoin.backend.service;

import com.autojoin.backend.model.BenchmarkSummary;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class BenchmarkResultStore {
    private final Map<String, BenchmarkSummary> results = new LinkedHashMap<>();

    public String save(BenchmarkSummary summary) {
        String id = UUID.randomUUID().toString();
        results.put(id, summary);
        return id;
    }

    public Optional<BenchmarkSummary> find(String id) {
        return Optional.ofNullable(results.get(id));
    }

    public List<StoredResult> list() {
        return results.entrySet().stream()
                .map(entry -> new StoredResult(entry.getKey(), entry.getValue()))
                .collect(Collectors.toList());
    }

    public record StoredResult(String id, BenchmarkSummary summary) {}
}