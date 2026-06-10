package com.autojoin.q_gram;

import java.util.*;

public class ColumnSuffixIndex {
    private final List<SuffixEntry> suffixArray;

    public ColumnSuffixIndex(List<String> columnData) {
        suffixArray = new ArrayList<>();
        buildIndex(columnData);
    }

    // columnData represents one full column of the table
    private void buildIndex(List<String> columnData) {
        for (int rowIndex = 0; rowIndex < columnData.size(); rowIndex++) {
            String val = columnData.get(rowIndex);
            if (val == null) continue;

            // Generate all suffixes for the value
            for (int i = 0; i < val.length(); i++) {
                suffixArray.add(new SuffixEntry(val.substring(i), rowIndex));
            }
        }
        // Sort the array alphabetically by the suffix string to enable binary search
        suffixArray.sort(Comparator.comparing(e -> e.suffixText));
    }

    // Binary search method to find the range of suffixes
    public List<Integer> findMatches(String qGram) {
        List<Integer> matchedRows = new ArrayList<>();

        if (qGram == null || qGram.isEmpty() || suffixArray.isEmpty()) {
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
            matchedRows.add(suffixArray.get(i).originalRowIndex);
        }

        return matchedRows;
    }

    /**
     * Whether any suffix starts with the qGram. Used by the BinarySearchQ probes
     * (Algorithm 3), which only need existence — unlike {@link #findMatches} this
     * does not materialize the matching row list.
     */
    public boolean hasMatch(String qGram) {
        if (qGram == null || qGram.isEmpty() || suffixArray.isEmpty()) {
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
        if (qGram == null || qGram.isEmpty() || suffixArray.isEmpty()) {
            return 0;
        }
        int startIndex = findMatchRange(qGram, true);
        if (startIndex == -1) {
            return 0;
        }
        int endIndex = findMatchRange(qGram, false);
        return endIndex - startIndex + 1;
    }

    /**
     * Binary search to find the range of suffixes that starts with the qGram.
     */
    private int findMatchRange(String qGram, boolean isSearchForFirstMatch) {
        int left = 0;
        int right = suffixArray.size() - 1;
        int result = -1;

        while (left <= right) {
            int mid = left + (right - left) / 2;
            String suffix = suffixArray.get(mid).suffixText;

            if (suffix.startsWith(qGram)) {
                result = mid;      // match found

                if (isSearchForFirstMatch) {
                    right = mid - 1; // Keep searching to the left
                } else {
                    left = mid + 1;  // Keep searching to the right
                }

            } else if (suffix.compareTo(qGram) < 0) { // compareTo compares strings lexicographically -> returns value < 0 if suffix is lexicographically less that qGram and value > 0 if suffix is lexicographically greater than qGram
                left = mid + 1;    // Suffix is alphabetically before the qGram -> move search to right
            } else {
                right = mid - 1;   // Suffix is alphabetically after the qGram -> move search to left
            }
        }
        return result;
    }
}
