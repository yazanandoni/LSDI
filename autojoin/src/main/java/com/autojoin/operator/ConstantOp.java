package com.autojoin.operator;

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