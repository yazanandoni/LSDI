package com.autojoin.synthesis;

/**
 * One training example for transformation learning: a source row (input) and
 * the target key value that transformation must produce from it (output).
 */
public class ExamplePair {

    /** Full source row — row[i] is the value of the i-th column. */
    public final String[] sourceRow;

    /** The target key-column value we need the transformation to produce. */
    public final String targetValue;

    public ExamplePair(String[] sourceRow, String targetValue) {
        this.sourceRow = sourceRow;
        this.targetValue = targetValue;
    }
}
