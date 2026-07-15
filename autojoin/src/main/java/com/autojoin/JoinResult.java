package com.autojoin;

import com.autojoin.model.Row;
import com.autojoin.trace.AlgorithmTrace;

import java.util.Collections;
import java.util.List;

public class JoinResult {

    private final List<Row[]> joinedPairs;
    private final String transformationDescription;
    private final AlgorithmTrace trace;

    private JoinResult(List<Row[]> joinedPairs, String transformationDescription, AlgorithmTrace trace) {
        this.joinedPairs = Collections.unmodifiableList(joinedPairs);
        this.transformationDescription = transformationDescription;
        this.trace = trace;
    }

    public static JoinResult of(List<Row[]> pairs, String transformationDescription, AlgorithmTrace trace) {
        return new JoinResult(pairs, transformationDescription, trace);
    }

    public static JoinResult of(List<Row[]> pairs, String transformationDescription) {
        return new JoinResult(pairs, transformationDescription, null);
    }

    public static JoinResult empty() {
        return new JoinResult(List.of(), null, null);
    }

    public List<Row[]> getJoinedPairs() { return joinedPairs; }
    public String getTransformationDescription() { return transformationDescription; }
    public AlgorithmTrace getTrace() { return trace; }
    public boolean isEmpty() { return joinedPairs.isEmpty(); }
    public int size() { return joinedPairs.size(); }

    @Override
    public String toString() {
        if (isEmpty()) return "JoinResult[empty]";
        return "JoinResult[" + size() + " pairs, transform=" + transformationDescription + "]";
    }
}