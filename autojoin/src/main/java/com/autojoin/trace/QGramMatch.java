package com.autojoin.trace;

public class QGramMatch {
    private final String sourceValue;
    private final String qGram;
    private final String targetValue;
    private final double score;

    public QGramMatch(String sourceValue, String qGram, String targetValue, double score) {
        this.sourceValue = sourceValue;
        this.qGram = qGram;
        this.targetValue = targetValue;
        this.score = score;
    }

    public String getSourceValue() { return sourceValue; }
    public String getQGram() { return qGram; }
    public String getTargetValue() { return targetValue; }
    public double getScore() { return score; }
}