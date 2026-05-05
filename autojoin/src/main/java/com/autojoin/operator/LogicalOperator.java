package com.autojoin.operator;

/**
 * A logical operator takes an input row (array of column values) and produces
 * a string that contributes one segment of the final transformed output.
 *
 * The four logical operators are:
 *   Constant, Substr, SplitSubstr, SplitSplitSubstr  (Appendix F, full paper).
 *
 * Concat is a physical operator but NOT a logical operator — it is implicit
 * in how the transformation tree concatenates the outputs of its nodes.
 */
public interface LogicalOperator {

    /**
     * Apply this operator to a row, where row[i] is the value of the i-th column.
     */
    String apply(String[] row);

    /** Human-readable description of this operator and its parameters. */
    String describe();
}