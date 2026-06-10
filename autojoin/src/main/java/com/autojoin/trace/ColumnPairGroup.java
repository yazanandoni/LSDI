package com.autojoin.trace;

import java.util.List;

public class ColumnPairGroup {
    private final String sourceColumnName;
    private final String targetColumnName;
    private final int matchCount;
    private final double avgScore;
    private final List<QGramMatch> topMatches;

    public ColumnPairGroup(String sourceColumnName, String targetColumnName,
                           int matchCount, double avgScore, List<QGramMatch> topMatches) {
        this.sourceColumnName = sourceColumnName;
        this.targetColumnName = targetColumnName;
        this.matchCount = matchCount;
        this.avgScore = avgScore;
        this.topMatches = topMatches;
    }

    public String getSourceColumnName() { return sourceColumnName; }
    public String getTargetColumnName() { return targetColumnName; }
    public int getMatchCount() { return matchCount; }
    public double getAvgScore() { return avgScore; }
    public List<QGramMatch> getTopMatches() { return topMatches; }
}