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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@RestController
@RequestMapping("/api")
public class BenchmarkController {
    private final BenchmarkService benchmarkService;
    private final BenchmarkResultStore resultStore;

    /**
     * Async runs: long benchmarks (a baseline burning its full timeout budget,
     * or AJ on the 1M table) cannot be served on one HTTP request — browsers
     * abort a request that produces no response for ~5 minutes, which surfaces
     * as "status 0 Unknown Error" in the UI. So the run endpoints below return
     * a job id immediately and the frontend polls. Jobs execute on a SINGLE
     * thread so concurrent submissions cannot run in parallel and distort the
     * timing measurements.
     */
    private final Map<String, JobState> jobs = new ConcurrentHashMap<>();
    private final ExecutorService jobExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "benchmark-job");
        t.setDaemon(true);
        return t;
    });

    private static final class JobState {
        final long startedMs = System.currentTimeMillis();
        volatile String status = "running";
        volatile String resultId;
        volatile String error;
    }

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
        String method = request.method() != null ? request.method() : "AJ";
        BenchmarkRunOutcome outcome = benchmarkService.runBenchmark(request.pairId(), method);
        String resultId = resultStore.save(outcome.summary());
        resultStore.saveCsv(resultId, outcome.csv());
        resultStore.saveTrace(resultId, outcome.trace());
        return ResponseEntity.ok(new ResultIdResponse(resultId));
    }

    /** Start a run in the background; poll /benchmarks/jobs/{id} for the result. */
    @PostMapping("/benchmarks/run-async")
    public ResponseEntity<Map<String, String>> runBenchmarkAsync(
            @Valid @RequestBody BenchmarkRunRequest request) {
        String method = request.method() != null ? request.method() : "AJ";
        String jobId = UUID.randomUUID().toString();
        JobState job = new JobState();
        jobs.put(jobId, job);
        jobExecutor.submit(() -> {
            try {
                BenchmarkRunOutcome outcome = benchmarkService.runBenchmark(request.pairId(), method);
                String resultId = resultStore.save(outcome.summary());
                resultStore.saveCsv(resultId, outcome.csv());
                resultStore.saveTrace(resultId, outcome.trace());
                job.resultId = resultId;
                job.status = "done";
            } catch (Exception e) {
                job.error = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                job.status = "error";
            }
        });
        return ResponseEntity.ok(Map.of("jobId", jobId));
    }

    @GetMapping("/benchmarks/jobs/{id}")
    public ResponseEntity<Map<String, String>> jobStatus(@PathVariable("id") String id) {
        JobState job = jobs.get(id);
        if (job == null) return ResponseEntity.notFound().build();
        Map<String, String> body = new HashMap<>();
        body.put("status", job.status);
        body.put("elapsedMs", String.valueOf(System.currentTimeMillis() - job.startedMs));
        if (job.resultId != null) body.put("resultId", job.resultId);
        if (job.error != null) body.put("error", job.error);
        return ResponseEntity.ok(body);
    }

    /**
     * Active runtime limits, so the UI can display what it is running under.
     * The JVM heap is fixed at process start — changing it means setting
     * BACKEND_HEAP (docker-compose) and recreating the backend container.
     */
    @GetMapping("/system/info")
    public Map<String, Object> systemInfo() {
        return Map.of(
                "maxHeapBytes", Runtime.getRuntime().maxMemory(),
                "baselineTimeoutSeconds", benchmarkService.getBaselineTimeoutSeconds());
    }

    @PostMapping("/benchmarks/run-all")
    public ResponseEntity<List<ResultIdResponse>> runAllBenchmarks() throws IOException {
        List<BenchmarkDescriptor> all = benchmarkService.listBenchmarks();
        List<String> pairIds = all.stream().map(BenchmarkDescriptor::pairId).toList();
        List<BenchmarkRunOutcome> outcomes = benchmarkService.runBenchmarks(pairIds, "AJ");
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
        List<String> methods = request.methods();
        List<BenchmarkRunOutcome> outcomes;
        if (methods != null && !methods.isEmpty()) {
            List<String> pairIds = request.pairIds();
            outcomes = benchmarkService.runBenchmarks(pairIds, methods);
        } else {
            outcomes = benchmarkService.runBenchmarks(request.pairIds(), "AJ");
        }
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
                summary.joinTimeMs(), summary.fuzzyTimeMs(),
                summary.method(), summary.timedOut());
    }
}