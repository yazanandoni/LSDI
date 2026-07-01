package com.autojoin.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class Row {

    private final List<String> columnNames;
    private final List<String> values;
    // Built lazily: the hot join loops (computeScore / applyJoin / fuzzyRecover)
    // create a Row per source row but only ever read getValues(), so eagerly
    // materializing a LinkedHashMap for every one of a million rows is pure
    // garbage. The map is only needed for name-keyed access (get(String),
    // asMap(), toString()), which the tracing/output paths use on the handful
    // of retained result rows. volatile: a Row may be read from several threads
    // (the two join directions run concurrently); the racy build is benign
    // because every thread computes the same map and the volatile write
    // publishes it safely.
    private volatile Map<String, String> data;

    /**
     * Public constructor. Keeps the original defensive-copy contract for
     * external callers that may pass mutable lists they later modify.
     */
    public Row(List<String> columnNames, List<String> values) {
        if (columnNames.size() != values.size()) {
            throw new IllegalArgumentException(
                "Column count (" + columnNames.size() + ") != value count (" + values.size() + ")");
        }
        this.columnNames = Collections.unmodifiableList(new ArrayList<>(columnNames));
        this.values = Collections.unmodifiableList(new ArrayList<>(values));
    }

    /** Trusted-path constructor: stores the lists by reference (no copy, no
     *  validation). Disambiguated from the public constructor by the marker arg. */
    private Row(List<String> columnNames, List<String> values, boolean trusted) {
        this.columnNames = columnNames;
        this.values = values;
    }

    /**
     * Fast Row construction for callers inside this package that already hold
     * immutable, equal-length, safe-to-share lists (e.g. {@link Table#getRow},
     * which shares one column-name list across every row). Skips the two
     * defensive copies and the eager map build.
     */
    static Row trusting(List<String> columnNames, List<String> values) {
        return new Row(columnNames, values, true);
    }

    private Map<String, String> data() {
        Map<String, String> local = data;
        if (local == null) {
            // Note: when two columns share a name, the map keeps the last value.
            // Positional access via get(int)/getValues() preserves every column.
            Map<String, String> map = new LinkedHashMap<>();
            for (int i = 0; i < columnNames.size(); i++) {
                map.put(columnNames.get(i), values.get(i));
            }
            local = Collections.unmodifiableMap(map);
            data = local;
        }
        return local;
    }

    public String get(String columnName) { return data().get(columnName); }
    public String get(int index) { return values.get(index); }
    public List<String> getColumnNames() { return columnNames; }
    public List<String> getValues() { return values; }
    public Map<String, String> asMap() { return data(); }
    public int size() { return columnNames.size(); }

    @Override
    public String toString() { return data().toString(); }
}
