package com.autojoin.trace;

public class AlgorithmTrace {
    private final boolean forwardWon;
    private final DirectionTrace forwardTrace;
    private final DirectionTrace backwardTrace;

    public AlgorithmTrace(boolean forwardWon, DirectionTrace forwardTrace, DirectionTrace backwardTrace) {
        this.forwardWon = forwardWon;
        this.forwardTrace = forwardTrace;
        this.backwardTrace = backwardTrace;
    }

    public boolean isForwardWon() { return forwardWon; }
    public DirectionTrace getForwardTrace() { return forwardTrace; }
    public DirectionTrace getBackwardTrace() { return backwardTrace; }
}