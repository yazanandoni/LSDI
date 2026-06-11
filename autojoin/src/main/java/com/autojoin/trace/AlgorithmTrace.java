package com.autojoin.trace;

public class AlgorithmTrace {
    private final boolean forwardWon;
    private final DirectionTrace forwardTrace;
    private final DirectionTrace backwardTrace;
    private final InputTablesTrace inputTables;

    public AlgorithmTrace(boolean forwardWon, DirectionTrace forwardTrace,
                          DirectionTrace backwardTrace, InputTablesTrace inputTables) {
        this.forwardWon = forwardWon;
        this.forwardTrace = forwardTrace;
        this.backwardTrace = backwardTrace;
        this.inputTables = inputTables;
    }

    public boolean isForwardWon() { return forwardWon; }
    public DirectionTrace getForwardTrace() { return forwardTrace; }
    public DirectionTrace getBackwardTrace() { return backwardTrace; }
    public InputTablesTrace getInputTables() { return inputTables; }
}