package com.autojoin.trace;

import java.util.List;

public class InputTablesTrace {
    private final TableInfo source;
    private final TableInfo target;

    public InputTablesTrace(TableInfo source, TableInfo target) {
        this.source = source;
        this.target = target;
    }

    public TableInfo getSource() { return source; }
    public TableInfo getTarget() { return target; }

    public static class TableInfo {
        private final String name;
        private final int rows;
        private final int columns;
        private final List<String> columnNames;
        private final List<String> keyColumns;
        private final List<List<String>> sampleRows;

        public TableInfo(String name, int rows, int columns,
                         List<String> columnNames, List<String> keyColumns,
                         List<List<String>> sampleRows) {
            this.name = name;
            this.rows = rows;
            this.columns = columns;
            this.columnNames = columnNames;
            this.keyColumns = keyColumns;
            this.sampleRows = sampleRows;
        }

        public String getName() { return name; }
        public int getRows() { return rows; }
        public int getColumns() { return columns; }
        public List<String> getColumnNames() { return columnNames; }
        public List<String> getKeyColumns() { return keyColumns; }
        public List<List<String>> getSampleRows() { return sampleRows; }
    }
}