package com.autojoin.q_gram;

import java.util.List;

public class MatchResult {
    String qGram;
    double score;
    /** Number of source / target rows the best q-gram occurs in (the n and m of Eq. 4). */
    int sourceFreq;
    int targetFreq;
    List<Integer> bestSourceRows;
    List<Integer> bestTargetRows;

    public MatchResult(String bestQGram, double bestScore, int sourceFreq, int targetFreq,
                       List<Integer> bestSourceRows, List<Integer> bestTargetRows) {
        this.qGram = bestQGram;
        this.score = bestScore;
        this.sourceFreq = sourceFreq;
        this.targetFreq = targetFreq;
        this.bestSourceRows = bestSourceRows;
        this.bestTargetRows = bestTargetRows;
    }

    public String getQGram() { return qGram; }
    public double getScore() { return score; }
    public int getSourceFreq() { return sourceFreq; }
    public int getTargetFreq() { return targetFreq; }
    /** n·m — 1 for a true 1-to-1 q-gram match (Definition 2). */
    public int getMatchProduct() { return sourceFreq * targetFreq; }
    public List<Integer> getBestSourceRows() { return bestSourceRows; }
    public List<Integer> getBestTargetRows() { return bestTargetRows; }
}