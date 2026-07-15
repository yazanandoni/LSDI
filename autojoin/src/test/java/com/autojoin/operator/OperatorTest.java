package com.autojoin.operator;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for all four logical operators using concrete examples from the paper.
 *
 * Figure 1 source rows (right table): "Last, First(year-)" format.
 * Figure 2 source rows (left table):  "First Last" full-name format.
 * Figure 4 source rows (left table):  ID + session name in separate fields.
 */
class OperatorTest {

    // Figure 1 — right table (source in Example 1)
    static final String[] OBAMA   = {"Obama, Barack(1961-)",   "47.0"};
    static final String[] BUSH    = {"Bush, George W.(1946-)", "49.4"};
    static final String[] CLINTON = {"Clinton, Bill(1946-)",   "55.1"};
    static final String[] REAGAN  = {"Reagan, Ronald(1911-2004)", "52.8"};

    // Figure 2 — left table
    static final String[] CHOWDHURY = {"Suhela Chowdhury", "Principal"};
    static final String[] MOORE     = {"Kelly Moore",      "Instructor"};

    // Figure 4 — left table
    static final String[] UBAX01 = {"UBAX01", "AXUG General Session"};
    static final String[] UBAX03 = {"UBAX03", "Master Planning Session"};

    // -------------------------------------------------------------------------
    // ConstantOp
    // -------------------------------------------------------------------------

    @Test
    void constantIgnoresRow() {
        ConstantOp space = new ConstantOp(" ");
        assertEquals(" ", space.apply(OBAMA));
        assertEquals(" ", space.apply(BUSH));
        assertEquals(" ", space.apply(new String[]{}));
    }

    @Test
    void constantEmailDomain() {
        ConstantOp domain = new ConstantOp("@forsyth.k12.ga.us");
        assertEquals("@forsyth.k12.ga.us", domain.apply(CHOWDHURY));
    }

    // -------------------------------------------------------------------------
    // SubstrOp
    // -------------------------------------------------------------------------

    @Test
    void substrFullSecondElement() {
        SubstrOp op = new SubstrOp(1, 0, -1, Casing.NONE);
        assertEquals("47.0", op.apply(OBAMA));
        assertEquals("49.4", op.apply(BUSH));
    }

    @Test
    void substrFirstElementLower() {
        SubstrOp op = new SubstrOp(0, 0, -1, Casing.LOWER);
        assertEquals("obama, barack(1961-)", op.apply(OBAMA));
    }

    // -------------------------------------------------------------------------
    // SplitSubstrOp — Figure 1: extract last name
    // -------------------------------------------------------------------------

    @Test
    void splitSubstrExtractsLastNameObama() {
        // "Obama, Barack(1961-)".split(",")[0] → "Obama"
        SplitSubstrOp op = new SplitSubstrOp(0, ",", 0, 0, -1, Casing.NONE);
        assertEquals("Obama", op.apply(OBAMA));
    }

    @Test
    void splitSubstrExtractsLastNameBush() {
        SplitSubstrOp op = new SplitSubstrOp(0, ",", 0, 0, -1, Casing.NONE);
        assertEquals("Bush", op.apply(BUSH));
    }

    @Test
    void splitSubstrExtractsLastNameClinton() {
        SplitSubstrOp op = new SplitSubstrOp(0, ",", 0, 0, -1, Casing.NONE);
        assertEquals("Clinton", op.apply(CLINTON));
    }

    @Test
    void splitSubstrExtractsLastNameReagan() {
        SplitSubstrOp op = new SplitSubstrOp(0, ",", 0, 0, -1, Casing.NONE);
        assertEquals("Reagan", op.apply(REAGAN));
    }

    // Figure 2: first initial from "First Last"
    @Test
    void splitSubstrFirstInitialChowdhury() {
        // "Suhela Chowdhury".split(" ")[0] → "Suhela", substring(0,1) → "S"
        SplitSubstrOp op = new SplitSubstrOp(0, " ", 0, 0, 1, Casing.LOWER);
        assertEquals("s", op.apply(CHOWDHURY));
    }

    // Figure 4: construct "[UBAX01] AXUG General Session"
    @Test
    void splitSubstrFigure4ExtractId() {
        SubstrOp id = new SubstrOp(0, 0, -1, Casing.NONE);
        assertEquals("UBAX01", id.apply(UBAX01));
    }

    // -------------------------------------------------------------------------
    // SplitSplitSubstrOp — Figure 1: extract first name (Example 7 in paper)
    // -------------------------------------------------------------------------

    @Test
    void splitSplitSubstrFirstNameObama() {
        SplitSplitSubstrOp op = new SplitSplitSubstrOp(0, "(", 0, ",", 1, 1, -1, Casing.NONE);
        assertEquals("Barack", op.apply(OBAMA));
    }

    @Test
    void splitSplitSubstrFirstNameBush() {
        SplitSplitSubstrOp op = new SplitSplitSubstrOp(0, "(", 0, ",", 1, 1, -1, Casing.NONE);
        assertEquals("George W.", op.apply(BUSH));
    }

    @Test
    void splitSplitSubstrFirstNameClinton() {
        SplitSplitSubstrOp op = new SplitSplitSubstrOp(0, "(", 0, ",", 1, 1, -1, Casing.NONE);
        assertEquals("Bill", op.apply(CLINTON));
    }

    @Test
    void splitSplitSubstrFirstNameReagan() {
        SplitSplitSubstrOp op = new SplitSplitSubstrOp(0, "(", 0, ",", 1, 1, -1, Casing.NONE);
        assertEquals("Ronald", op.apply(REAGAN));
    }


    private String applyFigure1Transform(String[] row) {
        SplitSplitSubstrOp firstName = new SplitSplitSubstrOp(0, "(", 0, ",", 1, 1, -1, Casing.NONE);
        ConstantOp space             = new ConstantOp(" ");
        SplitSubstrOp lastName       = new SplitSubstrOp(0, ",", 0, 0, -1, Casing.NONE);
        return firstName.apply(row) + space.apply(row) + lastName.apply(row);
    }

    @Test
    void fullTransformFigure1Obama() {
        assertEquals("Barack Obama", applyFigure1Transform(OBAMA));
    }

    @Test
    void fullTransformFigure1Bush() {
        assertEquals("George W. Bush", applyFigure1Transform(BUSH));
    }

    @Test
    void fullTransformFigure1Clinton() {
        assertEquals("Bill Clinton", applyFigure1Transform(CLINTON));
    }

    @Test
    void fullTransformFigure1Reagan() {
        assertEquals("Ronald Reagan", applyFigure1Transform(REAGAN));
    }


    private String applyFigure4Transform(String[] row) {
        ConstantOp open    = new ConstantOp("[");
        SubstrOp id        = new SubstrOp(0, 0, -1, Casing.NONE);
        ConstantOp closeSp = new ConstantOp("] ");
        SubstrOp session   = new SubstrOp(1, 0, -1, Casing.NONE);
        return open.apply(row) + id.apply(row) + closeSp.apply(row) + session.apply(row);
    }

    @Test
    void fullTransformFigure4Row1() {
        assertEquals("[UBAX01] AXUG General Session", applyFigure4Transform(UBAX01));
    }

    @Test
    void fullTransformFigure4Row3() {
        assertEquals("[UBAX03] Master Planning Session", applyFigure4Transform(UBAX03));
    }


    @Test
    void allDescribeMethodsReturnUsefulStrings() {
        assertFalse(new ConstantOp(" ").describe().isBlank());
        assertFalse(new SubstrOp(0, 0, -1, Casing.NONE).describe().isBlank());
        assertFalse(new SplitSubstrOp(0, ",", 0, 0, -1, Casing.NONE).describe().isBlank());
        assertFalse(new SplitSplitSubstrOp(0, "(", 0, ",", 1, 1, -1, Casing.NONE).describe().isBlank());
    }
}