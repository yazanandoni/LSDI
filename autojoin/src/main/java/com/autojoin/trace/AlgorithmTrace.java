package com.autojoin.trace;

public class AlgorithmTrace {
    private final boolean forwardWon;
    private final DirectionTrace forwardTrace;
    private final DirectionTrace backwardTrace;
    private final InputTablesTrace inputTables;
    private final long discoveryMs;
    private final long learningMs;
    private final long joinMs;
    private final long fuzzyMs;

    public AlgorithmTrace(boolean forwardWon, DirectionTrace forwardTrace,
                          DirectionTrace backwardTrace, InputTablesTrace inputTables) {
        this(forwardWon, forwardTrace, backwardTrace, inputTables, 0, 0, 0, 0);
    }

    public AlgorithmTrace(boolean forwardWon, DirectionTrace forwardTrace,
                          DirectionTrace backwardTrace, InputTablesTrace inputTables,
                          long discoveryMs, long learningMs, long joinMs, long fuzzyMs) {
        this.forwardWon = forwardWon;
        this.forwardTrace = forwardTrace;
        this.backwardTrace = backwardTrace;
        this.inputTables = inputTables;
        this.discoveryMs = discoveryMs;
        this.learningMs = learningMs;
        this.joinMs = joinMs;
        this.fuzzyMs = fuzzyMs;
    }

    public boolean isForwardWon() { return forwardWon; }
    public DirectionTrace getForwardTrace() { return forwardTrace; }
    public DirectionTrace getBackwardTrace() { return backwardTrace; }
    public InputTablesTrace getInputTables() { return inputTables; }
    public long getDiscoveryMs() { return discoveryMs; }
    public long getLearningMs() { return learningMs; }
    public long getJoinMs() { return joinMs; }
    public long getFuzzyMs() { return fuzzyMs; }
}