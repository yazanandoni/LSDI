package com.autojoin.trace;

public class SampleMatch {
    private final String sourceValue;
    private final String transformedKey;
    private final String matchedTargetValue;
    private final String status;

    public SampleMatch(String sourceValue, String transformedKey,
                       String matchedTargetValue, String status) {
        this.sourceValue = sourceValue;
        this.transformedKey = transformedKey;
        this.matchedTargetValue = matchedTargetValue;
        this.status = status;
    }

    public String getSourceValue() { return sourceValue; }
    public String getTransformedKey() { return transformedKey; }
    public String getMatchedTargetValue() { return matchedTargetValue; }
    public String getStatus() { return status; }
}