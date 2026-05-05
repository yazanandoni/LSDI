package com.autojoin.operator;

/**
 * Logical SplitSplitSubstr operator: two levels of split before Substring.
 *
 *   SplitSplitSubstr(row, k1, sep1, k2, sep2, m, start, length, casing) :=
 *       Substring(
 *           SelectK(Split(SelectK(Split(SelectK(row, k1), sep1), k2), sep2), m),
 *           start, length, casing)
 *
 * Example (Figure 1, row "Obama, Barack(1961-)"):
 *   k1=0  → "Obama, Barack(1961-)"
 *   sep1="(" → ["Obama, Barack", "1961-)"],  k2=0 → "Obama, Barack"
 *   sep2="," → ["Obama", " Barack"],         m=1  → " Barack"
 *   start=1, length=-1                               → "Barack"
 */
public class SplitSplitSubstrOp implements LogicalOperator {

    private final int k1;
    private final String sep1;
    private final int k2;
    private final String sep2;
    private final int m;
    private final int start;
    private final int length;
    private final Casing casing;

    public SplitSplitSubstrOp(int k1, String sep1, int k2, String sep2,
                               int m, int start, int length, Casing casing) {
        this.k1 = k1;
        this.sep1 = sep1;
        this.k2 = k2;
        this.sep2 = sep2;
        this.m = m;
        this.start = start;
        this.length = length;
        this.casing = casing;
    }

    @Override
    public String apply(String[] row) {
        String elem    = PhysicalOps.selectK(row, k1);
        String[] parts1 = PhysicalOps.split(elem, sep1);
        String part1   = PhysicalOps.selectK(parts1, k2);
        String[] parts2 = PhysicalOps.split(part1, sep2);
        String part2   = PhysicalOps.selectK(parts2, m);
        return PhysicalOps.substring(part2, start, length, casing);
    }

    @Override
    public String describe() {
        return String.format(
            "SplitSplitSubstr(row[%d], \"%s\", part[%d], \"%s\", part[%d], start=%d, len=%d, %s)",
            k1, sep1, k2, sep2, m, start, length, casing);
    }

    public int getK1()       { return k1; }
    public String getSep1()  { return sep1; }
    public int getK2()       { return k2; }
    public String getSep2()  { return sep2; }
    public int getM()        { return m; }
    public int getStart()    { return start; }
    public int getLength()   { return length; }
    public Casing getCasing(){ return casing; }
}