package com.autojoin.trace;

import java.util.List;

public class DiscoveryTrace {
    private final List<ColumnPairGroup> columnPairGroups;

    public DiscoveryTrace() {
        this.columnPairGroups = List.of();
    }

    public DiscoveryTrace(List<ColumnPairGroup> columnPairGroups) {
        this.columnPairGroups = columnPairGroups;
    }

    public List<ColumnPairGroup> getColumnPairGroups() { return columnPairGroups; }
}