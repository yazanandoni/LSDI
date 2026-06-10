package com.autojoin.trace;

import java.util.List;

public class ApplicationTrace {
    private final int totalSourceRows;
    private final int totalMatched;
    private final int totalUnmatched;
    private final List<SampleMatch> sampleMatches;

    public ApplicationTrace() {
        this(0, 0, 0, List.of());
    }

    public ApplicationTrace(int totalSourceRows, int totalMatched, int totalUnmatched,
                            List<SampleMatch> sampleMatches) {
        this.totalSourceRows = totalSourceRows;
        this.totalMatched = totalMatched;
        this.totalUnmatched = totalUnmatched;
        this.sampleMatches = sampleMatches;
    }

    public int getTotalSourceRows() { return totalSourceRows; }
    public int getTotalMatched() { return totalMatched; }
    public int getTotalUnmatched() { return totalUnmatched; }
    public List<SampleMatch> getSampleMatches() { return sampleMatches; }
}