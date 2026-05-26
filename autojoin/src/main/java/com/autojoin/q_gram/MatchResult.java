package com.autojoin.q_gram;

import java.util.List;

public class MatchResult {
    String qGram;
    double score;
    List<Integer> bestSourceRows;
    List<Integer> bestTargetRows;

    public MatchResult(String bestQGram, double bestScore, List<Integer> bestSourceRows, List<Integer> bestTargetRows) {
        this.qGram = bestQGram;
        this.score = bestScore;
        this.bestSourceRows = bestSourceRows;
        this.bestTargetRows = bestTargetRows;
    }

    public String getQGram() { return qGram; }
    public double getScore() { return score; }
    public List<Integer> getBestSourceRows() { return bestSourceRows; }
    public List<Integer> getBestTargetRows() { return bestTargetRows; }
}