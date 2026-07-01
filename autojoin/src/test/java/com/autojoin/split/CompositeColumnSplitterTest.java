package com.autojoin.split;

import com.autojoin.model.Column;
import com.autojoin.model.Table;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CompositeColumnSplitterTest {

    @Test
    void splitsCompositeNameLifespanColumn() {
        Column key = new Column("Name", List.of(
                "Obama, Barack (1961-)",
                "Bush, George (1924-)",
                "Clinton, Bill (1946-)",
                "Reagan, Ronald (1911-2004)"), true);
        Table table = new Table("t", List.of(key,
                new Column("Approval", List.of("47.0", "49.4", "55.1", "52.8"), false)));

        Table split = new CompositeColumnSplitter().splitKeyColumns(table);

        assertNotSame(table, split, "composite key should be split");
        assertTrue(split.numColumns() > table.numColumns(), "sub-columns should be added");
        assertTrue(split.getColumn("Name").isPresent(), "original key column retained");

        // The name part before " (" must be produced as a sub-column.
        List<String> namePart = List.of("Obama, Barack", "Bush, George", "Clinton, Bill", "Reagan, Ronald");
        boolean found = split.getColumns().stream().anyMatch(c -> c.getValues().equals(namePart));
        assertTrue(found, "expected a 'before (' sub-column holding the name part");

        // Every emitted sub-column is a key (so discovery treats it as a target).
        split.getColumns().stream()
                .filter(c -> !c.getName().equals("Name") && !c.getName().equals("Approval"))
                .forEach(c -> assertTrue(c.isKey(), c.getName() + " should be a key sub-column"));
    }

    @Test
    void leavesAtomicColumnUnchanged() {
        Table table = new Table("t", List.of(
                new Column("City", List.of("Paris", "Berlin", "Madrid", "Rome"), true),
                new Column("Pop", List.of("2", "3", "3", "4"), false)));
        assertSame(table, new CompositeColumnSplitter().splitKeyColumns(table),
                "a column with no consistent punctuation should not be split");
    }

    @Test
    void ignoresInconsistentPunctuation() {
        // '(' appears in only 1 of 4 values → below the coverage threshold.
        Table table = new Table("t", List.of(
                new Column("X", List.of("alpha (1)", "beta", "gamma", "delta"), true),
                new Column("v", List.of("1", "2", "3", "4"), false)));
        assertSame(table, new CompositeColumnSplitter().splitKeyColumns(table),
                "incidental punctuation should not trigger a split");
    }
}
