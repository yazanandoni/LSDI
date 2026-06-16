package com.autojoin.trace;

import java.util.List;

public class FuzzyTrace {
    private final int recoveredCount;
    private final double optimalThreshold;
    private final int unmatchedBeforeFuzzy;
    private final List<FuzzyRecoveryMatch> sampleRecovered;

    public FuzzyTrace() {
        this(0, 0.0, 0, List.of());
    }

    public FuzzyTrace(int recoveredCount, double optimalThreshold, int unmatchedBeforeFuzzy,
                      List<FuzzyRecoveryMatch> sampleRecovered) {
        this.recoveredCount = recoveredCount;
        this.optimalThreshold = optimalThreshold;
        this.unmatchedBeforeFuzzy = unmatchedBeforeFuzzy;
        this.sampleRecovered = sampleRecovered;
    }

    public int getRecoveredCount() { return recoveredCount; }
    public double getOptimalThreshold() { return optimalThreshold; }
    public int getUnmatchedBeforeFuzzy() { return unmatchedBeforeFuzzy; }
    public List<FuzzyRecoveryMatch> getSampleRecovered() { return sampleRecovered; }
}