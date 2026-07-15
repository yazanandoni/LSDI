package com.autojoin.operator;

public class SubstrOp implements LogicalOperator {

    private final int k;
    private final int start;
    private final int length;
    private final Casing casing;

    public SubstrOp(int k, int start, int length, Casing casing) {
        this.k = k;
        this.start = start;
        this.length = length;
        this.casing = casing;
    }

    @Override
    public String apply(String[] row) {
        String elem = PhysicalOps.selectK(row, k);
        return PhysicalOps.substring(elem, start, length, casing);
    }

    @Override
    public String describe() {
        return String.format("Substr(row[%d], start=%d, len=%d, %s)", k, start, length, casing);
    }

    public int getK()        { return k; }
    public int getStart()    { return start; }
    public int getLength()   { return length; }
    public Casing getCasing(){ return casing; }
}