package com.autojoin.trace;

public class FuzzyRecoveryMatch {
    private final String sourceValue;
    private final String targetValue;
    private final double jaccardDistance;

    public FuzzyRecoveryMatch(String sourceValue, String targetValue, double jaccardDistance) {
        this.sourceValue = sourceValue;
        this.targetValue = targetValue;
        this.jaccardDistance = jaccardDistance;
    }

    public String getSourceValue() { return sourceValue; }
    public String getTargetValue() { return targetValue; }
    public double getJaccardDistance() { return jaccardDistance; }
}