package com.autojoin.backend.controller;

import com.autojoin.JoinResult;
import com.autojoin.backend.model.BenchmarkSummary;
import com.autojoin.backend.model.Mismatch;
import com.autojoin.backend.service.BenchmarkResultStore;
import com.autojoin.backend.service.UploadService;
import com.autojoin.model.Row;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStreamReader;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api")
public class UploadController {
    private final UploadService uploadService;
    private final BenchmarkResultStore resultStore;

    public UploadController(UploadService uploadService, BenchmarkResultStore resultStore) {
        this.uploadService = uploadService;
        this.resultStore = resultStore;
    }

    @PostMapping("/uploads/join")
    public ResponseEntity<BenchmarkSummary> joinUpload(
            @RequestParam("source") MultipartFile sourceFile,
            @RequestParam("target") MultipartFile targetFile,
            @RequestParam("sourceKeys") String sourceKeysStr,
            @RequestParam("targetKeys") String targetKeysStr) throws Exception {

        List<String> sourceKeys = List.of(sourceKeysStr.split(","));
        List<String> targetKeys = List.of(targetKeysStr.split(","));

        try (Reader sourceReader = new InputStreamReader(sourceFile.getInputStream());
             Reader targetReader = new InputStreamReader(targetFile.getInputStream())) {
            JoinResult result = uploadService.joinCsv(sourceReader, targetReader, sourceKeys, targetKeys);

            if (result == null || result.isEmpty()) {
                return ResponseEntity.ok(new BenchmarkSummary(
                        "upload", "unknown", 0, 0, 0, 0.0, 0.0, null, List.of()));
            }

            List<Mismatch> mismatches = new ArrayList<>();
            int tp = result.size();
            for (Row[] pair : result.getJoinedPairs()) {
                Row srcRow = pair[0];
                Row tgtRow = pair[1];
                String srcFp = sourceKeys.stream().map(srcRow::get).collect(Collectors.joining("|"));
                String tgtFp = targetKeys.stream().map(tgtRow::get).collect(Collectors.joining(" | "));
                mismatches.add(new Mismatch(srcFp, List.of(tgtFp), tgtFp));
            }

            BenchmarkSummary summary = new BenchmarkSummary(
                    "upload", "source -> target", tp, result.size(), 0,
                    1.0, 1.0, result.getTransformationDescription(), mismatches);

            String resultId = resultStore.save(summary);
            return ResponseEntity.ok(summary);
        }
    }
}