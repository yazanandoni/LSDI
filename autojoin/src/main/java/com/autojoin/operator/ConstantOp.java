package com.autojoin.operator;

/**
 * Logical Constant operator: returns a fixed string regardless of input.
 *
 *   Constant(input) := input
 *
 * Used to produce separator strings (e.g. " ", "@") that are not derivable
 * from the source row.
 */
public class ConstantOp implements LogicalOperator {

    private final String value;

    public ConstantOp(String value) {
        this.value = value;
    }

    @Override
    public String apply(String[] row) {
        return value;
    }

    @Override
    public String describe() {
        return "Constant(\"" + value + "\")";
    }

    public String getValue() { return value; }
}