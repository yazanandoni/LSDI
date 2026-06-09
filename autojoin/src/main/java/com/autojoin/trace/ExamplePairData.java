package com.autojoin.trace;

public class ExamplePairData {
    private final String sourceValue;
    private final String targetValue;

    public ExamplePairData(String sourceValue, String targetValue) {
        this.sourceValue = sourceValue;
        this.targetValue = targetValue;
    }

    public String getSourceValue() { return sourceValue; }
    public String getTargetValue() { return targetValue; }
}