package com.autojoin.trace;

public class DemoMatch {
    private final String sourceValue;
    private final String transformedKey;
    private final String targetValue;
    private final boolean matches;

    public DemoMatch(String sourceValue, String transformedKey,
                     String targetValue, boolean matches) {
        this.sourceValue = sourceValue;
        this.transformedKey = transformedKey;
        this.targetValue = targetValue;
        this.matches = matches;
    }

    public String getSourceValue() { return sourceValue; }
    public String getTransformedKey() { return transformedKey; }
    public String getTargetValue() { return targetValue; }
    public boolean isMatches() { return matches; }
}