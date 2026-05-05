package com.autojoin;

import com.autojoin.model.Row;

import java.util.Collections;
import java.util.List;

public class JoinResult {

    /** Each element is a pair [sourceRow, targetRow]. */
    private final List<Row[]> joinedPairs;
    private final String transformationDescription;

    private JoinResult(List<Row[]> joinedPairs, String transformationDescription) {
        this.joinedPairs = Collections.unmodifiableList(joinedPairs);
        this.transformationDescription = transformationDescription;
    }

    public static JoinResult of(List<Row[]> pairs, String transformationDescription) {
        return new JoinResult(pairs, transformationDescription);
    }

    public static JoinResult empty() {
        return new JoinResult(List.of(), null);
    }

    public List<Row[]> getJoinedPairs() { return joinedPairs; }
    public String getTransformationDescription() { return transformationDescription; }
    public boolean isEmpty() { return joinedPairs.isEmpty(); }
    public int size() { return joinedPairs.size(); }

    @Override
    public String toString() {
        if (isEmpty()) return "JoinResult[empty]";
        return "JoinResult[" + size() + " pairs, transform=" + transformationDescription + "]";
    }
}