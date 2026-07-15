package com.autojoin.operator;

public class SplitSubstrOp implements LogicalOperator {

    private final int k;
    private final String sep;
    private final int m;
    private final int start;
    private final int length;
    private final Casing casing;

    public SplitSubstrOp(int k, String sep, int m, int start, int length, Casing casing) {
        this.k = k;
        this.sep = sep;
        this.m = m;
        this.start = start;
        this.length = length;
        this.casing = casing;
    }

    @Override
    public String apply(String[] row) {
        String elem = PhysicalOps.selectK(row, k);
        String[] parts = PhysicalOps.split(elem, sep);
        String part = PhysicalOps.selectK(parts, m);
        return PhysicalOps.substring(part, start, length, casing);
    }

    @Override
    public String describe() {
        return String.format("SplitSubstr(row[%d], \"%s\", part[%d], start=%d, len=%d, %s)",
                k, sep, m, start, length, casing);
    }

    public int getK()        { return k; }
    public String getSep()   { return sep; }
    public int getM()        { return m; }
    public int getStart()    { return start; }
    public int getLength()   { return length; }
    public Casing getCasing(){ return casing; }
}