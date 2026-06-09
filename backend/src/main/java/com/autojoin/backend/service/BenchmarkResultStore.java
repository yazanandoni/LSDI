package com.autojoin.backend.service;

import com.autojoin.backend.model.BenchmarkSummary;
import com.autojoin.trace.AlgorithmTrace;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class BenchmarkResultStore {
    private final Map<String, BenchmarkSummary> results = new LinkedHashMap<>();
    private final Map<String, String> resultCsvs = new LinkedHashMap<>();
    private final Map<String, AlgorithmTrace> traces = new LinkedHashMap<>();

    public String save(BenchmarkSummary summary) {
        String id = UUID.randomUUID().toString();
        results.put(id, summary);
        return id;
    }

    public String save(String id, BenchmarkSummary summary) {
        results.put(id, summary);
        return id;
    }

    public void saveCsv(String id, String csv) {
        resultCsvs.put(id, csv);
    }

    public void saveTrace(String id, AlgorithmTrace trace) {
        if (trace != null) {
            traces.put(id, trace);
        }
    }

    public Optional<String> getCsv(String id) {
        return Optional.ofNullable(resultCsvs.get(id));
    }

    public Optional<BenchmarkSummary> find(String id) {
        return Optional.ofNullable(results.get(id));
    }

    public Optional<AlgorithmTrace> getTrace(String id) {
        return Optional.ofNullable(traces.get(id));
    }

    public List<StoredResult> list() {
        List<StoredResult> stored = results.entrySet().stream()
                .map(entry -> new StoredResult(entry.getKey(), entry.getValue()))
                .collect(Collectors.toList());
        Collections.reverse(stored);
        return stored;
    }

    public record StoredResult(String id, BenchmarkSummary summary) {}
}