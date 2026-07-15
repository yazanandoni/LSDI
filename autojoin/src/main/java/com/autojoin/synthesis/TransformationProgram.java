package com.autojoin.synthesis;

import com.autojoin.operator.LogicalOperator;

import java.util.List;
import java.util.stream.Collectors;

public class TransformationProgram {

    private final List<LogicalOperator> operators;

    public TransformationProgram(List<LogicalOperator> operators) {
        this.operators = List.copyOf(operators);
    }

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
