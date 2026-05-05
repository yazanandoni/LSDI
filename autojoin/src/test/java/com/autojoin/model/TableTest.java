package com.autojoin.model;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.StringReader;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TableTest {

    // Tables from Figure 1 of the paper
    private Table presidentVotes() {
        return new Table("votes", List.of(
            new Column("President", List.of("Barack Obama", "George W. Bush", "Bill Clinton"), false),
            new Column("PopularVote", List.of("52.93%", "47.87%", "43.01%"), false)
        ));
    }

    private Table presidentRatings() {
        return new Table("ratings", List.of(
            new Column("President", List.of("Obama, Barack(1961-)", "Bush, George W.(1946-)", "Clinton, Bill(1946-)"), true),
            new Column("ApprovalRating", List.of("47.0", "49.4", "55.1"), false)
        ));
    }

    @Test
    void constructsCorrectly() {
        Table t = presidentVotes();
        assertEquals(3, t.numRows());
        assertEquals(2, t.numColumns());
        assertEquals("Barack Obama", t.getColumn(0).getValue(0));
        assertEquals("votes", t.getName());
    }

    @Test
    void getRowReturnsAllValues() {
        Table t = presidentVotes();
        Row row = t.getRow(0);
        assertEquals("Barack Obama", row.get("President"));
        assertEquals("52.93%", row.get("PopularVote"));
        assertEquals(2, row.size());
    }

    @Test
    void getRowByIndexMatchesByName() {
        Table t = presidentVotes();
        Row row = t.getRow(1);
        assertEquals(row.get("President"), row.get(0));
        assertEquals(row.get("PopularVote"), row.get(1));
    }

    @Test
    void keyColumnsAreFiltered() {
        Table t = presidentRatings();
        List<Column> keys = t.getKeyColumns();
        assertEquals(1, keys.size());
        assertEquals("President", keys.get(0).getName());
        assertFalse(t.getColumn("ApprovalRating").get().isKey());
    }

    @Test
    void columnWithKeyCreatesNewColumn() {
        Column original = new Column("President", List.of("Obama"), false);
        Column keyed = original.withKey(true);
        assertFalse(original.isKey());
        assertTrue(keyed.isKey());
        assertEquals(original.getValues(), keyed.getValues());
    }

    @Test
    void getColumnByNameReturnsCorrectColumn() {
        Table t = presidentVotes();
        assertTrue(t.getColumn("President").isPresent());
        assertEquals("Barack Obama", t.getColumn("President").get().getValue(0));
        assertTrue(t.getColumn("Missing").isEmpty());
    }

    @Test
    void getAllRowsHasCorrectCount() {
        Table t = presidentVotes();
        List<Row> rows = t.getRows();
        assertEquals(3, rows.size());
        assertEquals("Bill Clinton", rows.get(2).get("President"));
    }

    @Test
    void rejectsMismatchedColumnLengths() {
        var ex = assertThrows(IllegalArgumentException.class, () -> new Table("bad", List.of(
            new Column("A", List.of("x", "y")),
            new Column("B", List.of("a"))
        )));
        assertTrue(ex.getMessage().contains("Column 'B'"));
    }

    @Test
    void rejectsEmptyColumnList() {
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class, () -> new Table("empty", List.of()));
        assertTrue(ex.getMessage().contains("at least one column"));
    }

    @Test
    void loadsFromCsv() throws IOException {
        String csv = "President,PopularVote\nBarack Obama,52.93%\nGeorge W. Bush,47.87%\n";
        Table t = Table.fromCsv("test", new StringReader(csv), List.of("President"));
        assertEquals(2, t.numRows());
        assertEquals("Barack Obama", t.getColumn("President").get().getValue(0));
        assertTrue(t.getColumn("President").get().isKey());
        assertFalse(t.getColumn("PopularVote").get().isKey());
    }

    @Test
    void csvLoaderHandlesQuotedFields() throws IOException {
        String csv = "Name,Title\n\"Obama, Barack\",President\n";
        Table t = Table.fromCsv("test", new StringReader(csv), List.of());
        assertEquals("Obama, Barack", t.getColumn("Name").get().getValue(0));
    }
}