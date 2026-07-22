package com.autojoin.operator;

import java.util.regex.Pattern;

/**
 * Physical operators: Split, SelectK, Concat, Constant, Substring.
 * These are the atomic building blocks from which logical operators are composed
 * (Appendix D, full paper).
 */
public final class PhysicalOps {

    private PhysicalOps() {}

    /**
     * Splits v on sep, keeping all parts including empty trailing ones.
     * Uses literal (non-regex) matching for sep.
     */
    public static String[] split(String v, String sep) {
        if (sep.isEmpty()) throw new IllegalArgumentException("Separator cannot be empty");
        return v.split(Pattern.quote(sep), -1);
    }

    /**
     * Selects the k-th element from array.
     * Negative k counts from the end: -1 is last, -2 is second-to-last, etc.
     */
    public static String selectK(String[] array, int k) {
        int idx = k < 0 ? array.length + k : k;
        if (idx < 0 || idx >= array.length) {
            throw new IndexOutOfBoundsException(
                "k=" + k + " out of bounds for array length " + array.length);
        }
        return array[idx];
    }

    public static String constant(String v) {
        return v;
    }

    /**
     * Returns a substring of v.
     *
     * start: 0-based index; negative counts from end of string.
     * length: number of characters to take; -1 means "to end of string".
     * casing: casing transformation applied to the result.
     */
    public static String substring(String v, int start, int length, Casing casing) {
        int len = v.length();
        int s = start < 0 ? len + start : start;
        s = Math.max(0, Math.min(s, len));
        int end = (length == -1) ? len : Math.min(s + length, len);
        if (s >= end) return "";
        return casing.apply(v.substring(s, end));
    }
}