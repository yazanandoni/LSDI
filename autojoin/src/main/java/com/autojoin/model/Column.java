package com.autojoin.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class Column {

    private final String name;
    private final List<String> values;
    private final boolean key;

    public Column(String name, List<String> values, boolean key) {
        this.name = name;
        this.values = Collections.unmodifiableList(new ArrayList<>(values));
        this.key = key;
    }

    public Column(String name, List<String> values) {
        this(name, values, false);
    }

    public String getName() { return name; }
    public List<String> getValues() { return values; }
    public String getValue(int rowIndex) { return values.get(rowIndex); }
    public boolean isKey() { return key; }
    public int size() { return values.size(); }

    public Column withKey(boolean key) {
        return new Column(name, values, key);
    }
}