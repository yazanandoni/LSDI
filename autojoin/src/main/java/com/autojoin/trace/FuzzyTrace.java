package com.autojoin.trace;

import java.util.List;

public class FuzzyTrace {
    private final int recoveredCount;
    private final double optimalThreshold;
    private final int unmatchedBeforeFuzzy;
    private final int remainingUnmatched;
    private final List<FuzzyRecoveryMatch> sampleRecovered;

    public FuzzyTrace() {
        this(0, 0.0, 0, 0, List.of());
    }

    public FuzzyTrace(int recoveredCount, double optimalThreshold, int unmatchedBeforeFuzzy,
                      int remainingUnmatched, List<FuzzyRecoveryMatch> sampleRecovered) {
        this.recoveredCount = recoveredCount;
        this.optimalThreshold = optimalThreshold;
        this.unmatchedBeforeFuzzy = unmatchedBeforeFuzzy;
        this.remainingUnmatched = remainingUnmatched;
        this.sampleRecovered = sampleRecovered;
    }

    public int getRecoveredCount() { return recoveredCount; }
    public double getOptimalThreshold() { return optimalThreshold; }
    public int getUnmatchedBeforeFuzzy() { return unmatchedBeforeFuzzy; }
    public int getRemainingUnmatched() { return remainingUnmatched; }
    public List<FuzzyRecoveryMatch> getSampleRecovered() { return sampleRecovered; }
}