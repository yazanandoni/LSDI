package com.autojoin.q_gram;

import java.util.ArrayList;
import java.util.List;

public class ColumnSuffixIndex {
    /** The column's value strings, indexed by original row. */
    private final String[] values;

    /**
     * One entry per suffix, encoded as (rowIndex << 32) | startOffset and
     * sorted by the suffix text each entry denotes. Suffixes are never
     * materialised — comparisons read characters from {@link #values} in
     * place. The previous implementation stored every suffix as a copied
     * String (O(total-chars²) memory), which on large sampled tables (the
     * 1M DBLP benchmark) blew the heap and stalled runs in GC.
     * Ties (equal suffix text) order by encoded value, i.e. row then offset —
     * exactly the order the old stable sort over insertion order produced.
     */
    private final long[] entries;

    public ColumnSuffixIndex(List<String> columnData) {
        values = columnData.toArray(new String[0]);
        long total = 0;
        for (String val : values) {
            if (val != null) total += val.length();
        }
        if (total > Integer.MAX_VALUE) {
            throw new IllegalStateException("column too large to suffix-index: " + total + " chars");
        }
        entries = new long[(int) total];
        int k = 0;
        for (int row = 0; row < values.length; row++) {
            String val = values[row];
            if (val == null) continue;
            for (int off = 0; off < val.length(); off++) {
                entries[k++] = ((long) row << 32) | off;
            }
        }
        mergeSort(entries, new long[entries.length], 0, entries.length);
    }

    /** Lexicographic suffix comparison (String.compareTo semantics), reading
     *  chars in place; equal suffixes tie-break on (row, offset). */
    private int compare(long e1, long e2) {
        String v1 = values[(int) (e1 >>> 32)];
        String v2 = values[(int) (e2 >>> 32)];
        int o1 = (int) e1;
        int o2 = (int) e2;
        int l1 = v1.length() - o1;
        int l2 = v2.length() - o2;
        int n = Math.min(l1, l2);
        for (int i = 0; i < n; i++) {
            char c1 = v1.charAt(o1 + i);
            char c2 = v2.charAt(o2 + i);
            if (c1 != c2) return c1 - c2;
        }
        if (l1 != l2) return l1 - l2;
        return Long.compare(e1, e2);
    }

    private void mergeSort(long[] a, long[] tmp, int lo, int hi) {
        if (hi - lo <= 24) {
            for (int i = lo + 1; i < hi; i++) {
                long e = a[i];
                int j = i - 1;
                while (j >= lo && compare(a[j], e) > 0) {
                    a[j + 1] = a[j];
                    j--;
                }
                a[j + 1] = e;
            }
            return;
        }
        int mid = (lo + hi) >>> 1;
        mergeSort(a, tmp, lo, mid);
        mergeSort(a, tmp, mid, hi);
        if (compare(a[mid - 1], a[mid]) <= 0) return;
        System.arraycopy(a, lo, tmp, lo, hi - lo);
        int i = lo, j = mid, k = lo;
        while (i < mid && j < hi) {
            a[k++] = compare(tmp[i], tmp[j]) <= 0 ? tmp[i++] : tmp[j++];
        }
        while (i < mid) a[k++] = tmp[i++];
        while (j < hi) a[k++] = tmp[j++];
    }

    // Binary search method to find the range of suffixes
    public List<Integer> findMatches(String qGram) {
        List<Integer> matchedRows = new ArrayList<>();

        if (qGram == null || qGram.isEmpty() || entries.length == 0) {
            return matchedRows;
        }

        // Find the starting index of the matching block
        int startIndex = findMatchRange(qGram, true);
        if (startIndex == -1) {
            return matchedRows; // No matches found -> return empty list
        }

        // Find the ending index of the matching block
        int endIndex = findMatchRange(qGram, false);

        //  Extract the original row numbers from that block
        for (int i = startIndex; i <= endIndex; i++) {
            matchedRows.add((int) (entries[i] >>> 32));
        }

        return matchedRows;
    }

    /**
     * Whether any suffix starts with the qGram. Used by the BinarySearchQ probes
     * (Algorithm 3), which only need existence — unlike {@link #findMatches} this
     * does not materialize the matching row list.
     */
    public boolean hasMatch(String qGram) {
        if (qGram == null || qGram.isEmpty() || entries.length == 0) {
            return false;
        }
        return findMatchRange(qGram, true) != -1;
    }

    /**
     * Number of suffixes starting with the qGram (the n / m of the 1/(n·m)
     * goodness score). Computed from the range boundaries without collecting
     * the row indices.
     */
    public int countMatches(String qGram) {
        if (qGram == null || qGram.isEmpty() || entries.length == 0) {
            return 0;
        }
        int startIndex = findMatchRange(qGram, true);
        if (startIndex == -1) {
            return 0;
        }
        int endIndex = findMatchRange(qGram, false);
        return endIndex - startIndex + 1;
    }

    /** Compare the suffix an entry denotes against a qGram: 0 when the suffix
     *  starts with the qGram, otherwise the String.compareTo sign. */
    private int compareToQGram(long e, String qGram) {
        String val = values[(int) (e >>> 32)];
        int off = (int) e;
        int slen = val.length() - off;
        int qlen = qGram.length();
        int n = Math.min(slen, qlen);
        for (int i = 0; i < n; i++) {
            char cs = val.charAt(off + i);
            char cq = qGram.charAt(i);
            if (cs != cq) return cs - cq;
        }
        return slen < qlen ? -1 : 0;
    }

    /**
     * Binary search to find the range of suffixes that starts with the qGram.
     */
    private int findMatchRange(String qGram, boolean isSearchForFirstMatch) {
        int left = 0;
        int right = entries.length - 1;
        int result = -1;

        while (left <= right) {
            int mid = left + (right - left) / 2;
            int cmp = compareToQGram(entries[mid], qGram);

            if (cmp == 0) {
                result = mid;      // match found

                if (isSearchForFirstMatch) {
                    right = mid - 1; // Keep searching to the left
                } else {
                    left = mid + 1;  // Keep searching to the right
                }

            } else if (cmp < 0) {
                left = mid + 1;    // Suffix is alphabetically before the qGram -> move search to right
            } else {
                right = mid - 1;   // Suffix is alphabetically after the qGram -> move search to left
            }
        }
        return result;
    }
}
