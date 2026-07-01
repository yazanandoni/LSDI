package com.autojoin.backend.controller;

import com.autojoin.backend.model.BatchRunRequest;
import com.autojoin.backend.model.BenchmarkDescriptor;
import com.autojoin.backend.model.BenchmarkRunRequest;
import com.autojoin.backend.model.BenchmarkSummary;
import com.autojoin.backend.model.BenchmarkSummaryView;
import com.autojoin.backend.model.ResultIdResponse;
import com.autojoin.backend.service.BenchmarkResultStore;
import com.autojoin.backend.service.BenchmarkService;
import com.autojoin.backend.service.BenchmarkService.BenchmarkRunOutcome;
import com.autojoin.trace.AlgorithmTrace;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping("/api")
public class BenchmarkController {
    private final BenchmarkService benchmarkService;
    private final BenchmarkResultStore resultStore;

    public BenchmarkController(BenchmarkService benchmarkService, BenchmarkResultStore resultStore) {
        this.benchmarkService = benchmarkService;
        this.resultStore = resultStore;
    }

    @GetMapping("/benchmarks")
    public List<BenchmarkDescriptor> listBenchmarks() throws IOException {
        return benchmarkService.listBenchmarks();
    }

    @PostMapping("/benchmarks/run")
    public ResponseEntity<ResultIdResponse> runBenchmark(@Valid @RequestBody BenchmarkRunRequest request)
            throws IOException {
        BenchmarkRunOutcome outcome = benchmarkService.runBenchmark(request.pairId());
        String resultId = resultStore.save(outcome.summary());
        resultStore.saveCsv(resultId, outcome.csv());
        resultStore.saveTrace(resultId, outcome.trace());
        return ResponseEntity.ok(new ResultIdResponse(resultId));
    }

    @PostMapping("/benchmarks/run-all")
    public ResponseEntity<List<ResultIdResponse>> runAllBenchmarks() throws IOException {
        List<BenchmarkDescriptor> all = benchmarkService.listBenchmarks();
        List<String> pairIds = all.stream().map(BenchmarkDescriptor::pairId).toList();
        List<BenchmarkRunOutcome> outcomes = benchmarkService.runBenchmarks(pairIds);
        List<ResultIdResponse> ids = outcomes.stream()
                .map(o -> {
                    String id = resultStore.save(o.summary());
                    resultStore.saveCsv(id, o.csv());
                    resultStore.saveTrace(id, o.trace());
                    return new ResultIdResponse(id);
                })
                .toList();
        return ResponseEntity.ok(ids);
    }

    @PostMapping("/benchmarks/run-batch")
    public ResponseEntity<List<ResultIdResponse>> runBatch(@RequestBody BatchRunRequest request)
            throws IOException {
        List<BenchmarkRunOutcome> outcomes = benchmarkService.runBenchmarks(request.pairIds());
        List<ResultIdResponse> ids = outcomes.stream()
                .map(o -> {
                    String id = resultStore.save(o.summary());
                    resultStore.saveCsv(id, o.csv());
                    resultStore.saveTrace(id, o.trace());
                    return new ResultIdResponse(id);
                })
                .toList();
        return ResponseEntity.ok(ids);
    }

    @GetMapping("/results/{id}")
    public ResponseEntity<BenchmarkSummaryView> getResult(@PathVariable("id") String id) {
        return resultStore.find(id)
                .map(summary -> ResponseEntity.ok(toView(id, summary)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/results/{id}/csv")
    public ResponseEntity<byte[]> getResultCsv(@PathVariable("id") String id) {
        return resultStore.getCsv(id)
                .map(csv -> ResponseEntity.ok()
                        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"result-" + id + ".csv\"")
                        .contentType(MediaType.TEXT_PLAIN)
                        .body(csv.getBytes()))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/results/{id}/trace")
    public ResponseEntity<AlgorithmTrace> getResultTrace(@PathVariable("id") String id) {
        return resultStore.getTrace(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/results")
    public List<BenchmarkSummaryView> listResults() {
        return resultStore.list().stream()
                .map(stored -> toView(stored.id(), stored.summary()))
                .toList();
    }

    private static BenchmarkSummaryView toView(String id, BenchmarkSummary summary) {
        return new BenchmarkSummaryView(
                id, summary.pairId(), summary.direction(),
                summary.truePositives(), summary.joinedPairs(), summary.groundTruthPairs(),
                summary.precision(), summary.recall(), summary.durationMs(),
                summary.transformation(), summary.mismatches(),
                summary.indexingTimeMs(), summary.learningTimeMs(),
                summary.joinTimeMs(), summary.fuzzyTimeMs());
    }
}