package com.autojoin.trace;

import java.util.List;

public class FuzzyTrace {
    private final int recoveredCount;
    private final double optimalThreshold;
    private final int unmatchedBeforeFuzzy;
    private final int remainingUnmatched;
    private final List<FuzzyRecoveryMatch> sampleRecovered;
    private final boolean skipped;

    public FuzzyTrace() {
        this(0, 0.0, 0, 0, List.of(), true);
    }

    public FuzzyTrace(int recoveredCount, double optimalThreshold, int unmatchedBeforeFuzzy,
                      int remainingUnmatched, List<FuzzyRecoveryMatch> sampleRecovered) {
        this(recoveredCount, optimalThreshold, unmatchedBeforeFuzzy,
                remainingUnmatched, sampleRecovered, false);
    }

    public FuzzyTrace(int unmatchedBeforeFuzzy, int remainingUnmatched, boolean skipped) {
        this(0, 0.0, unmatchedBeforeFuzzy, remainingUnmatched, List.of(), skipped);
    }

    private FuzzyTrace(int recoveredCount, double optimalThreshold, int unmatchedBeforeFuzzy,
                       int remainingUnmatched, List<FuzzyRecoveryMatch> sampleRecovered,
                       boolean skipped) {
        this.recoveredCount = recoveredCount;
        this.optimalThreshold = optimalThreshold;
        this.unmatchedBeforeFuzzy = unmatchedBeforeFuzzy;
        this.remainingUnmatched = remainingUnmatched;
        this.sampleRecovered = sampleRecovered;
        this.skipped = skipped;
    }

    public int getRecoveredCount() { return recoveredCount; }
    public double getOptimalThreshold() { return optimalThreshold; }
    public int getUnmatchedBeforeFuzzy() { return unmatchedBeforeFuzzy; }
    public int getRemainingUnmatched() { return remainingUnmatched; }
    public List<FuzzyRecoveryMatch> getSampleRecovered() { return sampleRecovered; }
    public boolean isSkipped() { return skipped; }
}