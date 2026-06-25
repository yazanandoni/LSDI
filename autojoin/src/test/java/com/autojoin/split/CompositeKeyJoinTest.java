package com.autojoin.split;

import com.autojoin.AutoJoin;
import com.autojoin.JoinResult;
import com.autojoin.model.Column;
import com.autojoin.model.Row;
import com.autojoin.model.Table;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Paper §3.3 end-to-end. Both key columns are composite, with DIFFERENT extra
 * fields (party on the left, life span on the right), so neither join direction
 * can reproduce the other side's full composite key — the party is absent from
 * the right, the life span absent from the left. Only after the composite keys
 * are split into component sub-columns can the name parts be transformation-
 * joined. A success here therefore exercises the §3.3 split-and-retry path.
 */
class CompositeKeyJoinTest {

    @Test
    void joinsWhenBothKeysAreComposite() {
        Table left = new Table("left", List.of(
                new Column("Person", List.of(
                        "Barack Obama (Democratic)",
                        "George Bush (Republican)",
                        "Bill Clinton (Democratic)",
                        "Ronald Reagan (Republican)"), true),
                new Column("Approval", List.of("47.0", "49.4", "55.1", "52.8"), false)));

        Table right = new Table("right", List.of(
                new Column("Name", List.of(
                        "Obama, Barack (1961-)",
                        "Bush, George (1924-)",
                        "Clinton, Bill (1946-)",
                        "Reagan, Ronald (1911-2004)"), true),
                new Column("PopularVote", List.of("52.9", "47.9", "43.0", "50.8"), false)));

        JoinResult result = new AutoJoin().join(left, right);

        assertFalse(result.isEmpty(), "composite-composite join should succeed via §3.3 splitting");
        assertTrue(result.size() >= 3, "most rows should join, got " + result.size());

        boolean obamaPaired = result.getJoinedPairs().stream().anyMatch(p -> {
            String person = valueOf(p, "Person");
            String name = valueOf(p, "Name");
            return person != null && person.startsWith("Barack Obama")
                    && name != null && name.startsWith("Obama, Barack");
        });
        assertTrue(obamaPaired, "the Obama rows should be correctly aligned across the two tables");
    }

    /** Read a column value from whichever side of the joined pair has it. */
    private static String valueOf(Row[] pair, String column) {
        for (Row r : pair) {
            String v = r.get(column);
            if (v != null) return v;
        }
        return null;
    }
}
