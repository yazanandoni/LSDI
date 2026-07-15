package com.autojoin.operator;

public interface LogicalOperator {

    String apply(String[] row);

    String describe();
}