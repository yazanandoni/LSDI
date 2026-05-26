package com.autojoin.synthesis;

import com.autojoin.operator.LogicalOperator;

import java.util.List;
import java.util.stream.Collectors;

/**
 * A transformation program is an ordered list of logical operators whose
 * outputs are concatenated (via implicit Concat) to produce the final string.
 *
 * This corresponds to Definition 4 in the paper: P = θ1 · θ2 · … · θn.
 */
public class TransformationProgram {

    private final List<LogicalOperator> operators;

    public TransformationProgram(List<LogicalOperator> operators) {
        this.operators = List.copyOf(operators);
    }

    /**
     * Apply the program to a source row, returning the concatenated output of
     * all operators, or null if any operator throws.
     */
    public String apply(String[] row) {
        StringBuilder sb = new StringBuilder();
        for (LogicalOperator op : operators) {
            try {
                String part = op.apply(row);
                if (part == null) return null;
                sb.append(part);
            } catch (Exception e) {
                return null;
            }
        }
        return sb.toString();
    }

    /** Number of logical operators — used as the complexity measure (Definition 4). */
    public int complexity() {
        return operators.size();
    }

    public List<LogicalOperator> getOperators() {
        return operators;
    }

    public String describe() {
        if (operators.isEmpty()) return "<identity>";
        return operators.stream().map(LogicalOperator::describe).collect(Collectors.joining(" + "));
    }

    @Override
    public String toString() {
        return describe();
    }
}
