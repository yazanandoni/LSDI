package com.autojoin.operator;

import java.util.regex.Pattern;

public final class PhysicalOps {

    private PhysicalOps() {}

    public static String[] split(String v, String sep) {
        if (sep.isEmpty()) throw new IllegalArgumentException("Separator cannot be empty");
        return v.split(Pattern.quote(sep), -1);
    }

    public static String selectK(String[] array, int k) {
        int idx = k < 0 ? array.length + k : k;
        if (idx < 0 || idx >= array.length) {
            throw new IndexOutOfBoundsException(
                "k=" + k + " out of bounds for array length " + array.length);
        }
        return array[idx];
    }

    public static String concat(String u, String v) {
        return u + v;
    }

    public static String constant(String v) {
        return v;
    }

    public static String substring(String v, int start, int length, Casing casing) {
        int len = v.length();
        int s = start < 0 ? len + start : start;
        s = Math.max(0, Math.min(s, len));
        int end = (length == -1) ? len : Math.min(s + length, len);
        if (s >= end) return "";
        return casing.apply(v.substring(s, end));
    }
}