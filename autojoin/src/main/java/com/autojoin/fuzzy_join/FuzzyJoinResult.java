package com.autojoin.fuzzy_join;

public class FuzzyJoinResult {
    public int sourceRowIndex;
    public int targetRowIndex;
    public double distance;

    public FuzzyJoinResult(int sourceRowIndex, int targetRowIndex, double distance) {
        this.sourceRowIndex = sourceRowIndex;
        this.targetRowIndex = targetRowIndex;
        this.distance = distance;
    }
}
