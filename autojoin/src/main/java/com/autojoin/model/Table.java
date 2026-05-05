package com.autojoin.model;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

public class Table {

    private final String name;
    private final List<Column> columns;

    public Table(String name, List<Column> columns) {
        if (columns.isEmpty()) {
            throw new IllegalArgumentException("Table must have at least one column");
        }
        int numRows = columns.get(0).size();
        for (Column col : columns) {
            if (col.size() != numRows) {
                throw new IllegalArgumentException(
                    "Column '" + col.getName() + "' has " + col.size() +
                    " rows but expected " + numRows);
            }
        }
        this.name = name;
        this.columns = Collections.unmodifiableList(new ArrayList<>(columns));
    }

    public String getName() { return name; }
    public int numRows() { return columns.get(0).size(); }
    public int numColumns() { return columns.size(); }
    public List<Column> getColumns() { return columns; }

    public Column getColumn(int index) { return columns.get(index); }

    public Optional<Column> getColumn(String name) {
        return columns.stream().filter(c -> c.getName().equals(name)).findFirst();
    }

    public List<Column> getKeyColumns() {
        return columns.stream().filter(Column::isKey).collect(Collectors.toList());
    }

    public Row getRow(int index) {
        List<String> colNames = columns.stream().map(Column::getName).collect(Collectors.toList());
        List<String> values = columns.stream().map(c -> c.getValue(index)).collect(Collectors.toList());
        return new Row(colNames, values);
    }

    public List<Row> getRows() {
        List<Row> rows = new ArrayList<>(numRows());
        for (int i = 0; i < numRows(); i++) {
            rows.add(getRow(i));
        }
        return rows;
    }

    /**
     * Load a table from CSV. The first row is treated as the header.
     * Columns whose names appear in keyColumns are marked as key columns.
     */
    public static Table fromCsv(String name, Reader reader, List<String> keyColumns) throws IOException {
        List<String[]> rawRows = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(reader)) {
            String line;
            while ((line = br.readLine()) != null) {
                if (!line.isBlank()) {
                    rawRows.add(parseCsvLine(line));
                }
            }
        }
        if (rawRows.isEmpty()) {
            throw new IllegalArgumentException("CSV is empty");
        }

        String[] header = rawRows.get(0);
        int numCols = header.length;

        List<List<String>> colValues = new ArrayList<>();
        for (int i = 0; i < numCols; i++) {
            colValues.add(new ArrayList<>());
        }

        for (int r = 1; r < rawRows.size(); r++) {
            String[] row = rawRows.get(r);
            for (int c = 0; c < numCols; c++) {
                colValues.get(c).add(c < row.length ? row[c].trim() : "");
            }
        }

        List<Column> columns = new ArrayList<>();
        for (int c = 0; c < numCols; c++) {
            String colName = header[c].trim();
            boolean isKey = keyColumns.contains(colName);
            columns.add(new Column(colName, colValues.get(c), isKey));
        }
        return new Table(name, columns);
    }

    private static String[] parseCsvLine(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                inQuotes = !inQuotes;
            } else if (c == ',' && !inQuotes) {
                fields.add(sb.toString());
                sb.setLength(0);
            } else {
                sb.append(c);
            }
        }
        fields.add(sb.toString());
        return fields.toArray(new String[0]);
    }

    @Override
    public String toString() {
        return "Table[" + name + ", " + numRows() + " rows x " + numColumns() + " cols]";
    }
}