package com.autojoin.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class Row {

    private final List<String> columnNames;
    private final List<String> values;
    private final Map<String, String> data;

    public Row(List<String> columnNames, List<String> values) {
        if (columnNames.size() != values.size()) {
            throw new IllegalArgumentException(
                "Column count (" + columnNames.size() + ") != value count (" + values.size() + ")");
        }
        this.columnNames = Collections.unmodifiableList(new ArrayList<>(columnNames));
        this.values = Collections.unmodifiableList(new ArrayList<>(values));
        // Note: when two columns share a name, the map keeps the last value.
        // Positional access via get(int)/getValues() preserves every column.
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < columnNames.size(); i++) {
            map.put(columnNames.get(i), values.get(i));
        }
        this.data = Collections.unmodifiableMap(map);
    }

    public String get(String columnName) { return data.get(columnName); }
    public String get(int index) { return values.get(index); }
    public List<String> getColumnNames() { return columnNames; }
    public List<String> getValues() { return values; }
    public Map<String, String> asMap() { return data; }
    public int size() { return columnNames.size(); }

    @Override
    public String toString() { return data.toString(); }
}