package com.autojoin.operator;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PhysicalOpsTest {

    // --- split ---

    @Test
    void splitOnParenthesis() {
        String[] parts = PhysicalOps.split("Obama, Barack(1961-)", "(");
        assertArrayEquals(new String[]{"Obama, Barack", "1961-)"}, parts);
    }

    @Test
    void splitOnComma() {
        String[] parts = PhysicalOps.split("Obama, Barack", ",");
        assertArrayEquals(new String[]{"Obama", " Barack"}, parts);
    }

    @Test
    void splitKeepsTrailingEmptyParts() {
        String[] parts = PhysicalOps.split("a,b,", ",");
        assertEquals(3, parts.length);
        assertEquals("", parts[2]);
    }

    @Test
    void splitOnMultiCharSeparator() {
        String[] parts = PhysicalOps.split("[UBAX01] AXUG", "] ");
        assertArrayEquals(new String[]{"[UBAX01", "AXUG"}, parts);
    }

    @Test
    void splitRejectsEmptySeparator() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> PhysicalOps.split("abc", ""));
        assertTrue(ex.getMessage().contains("empty"));
    }

    // --- selectK ---

    @Test
    void selectKPositiveIndex() {
        String[] arr = {"Obama", "Barack", "1961-)"};
        assertEquals("Obama",   PhysicalOps.selectK(arr, 0));
        assertEquals("Barack",  PhysicalOps.selectK(arr, 1));
        assertEquals("1961-)",  PhysicalOps.selectK(arr, 2));
    }

    @Test
    void selectKNegativeIndex() {
        String[] arr = {"Obama", "Barack", "1961-)"};
        assertEquals("1961-)",  PhysicalOps.selectK(arr, -1));
        assertEquals("Barack",  PhysicalOps.selectK(arr, -2));
        assertEquals("Obama",   PhysicalOps.selectK(arr, -3));
    }

    @Test
    void selectKOutOfBoundsThrows() {
        String[] arr = {"a", "b"};
        assertThrows(IndexOutOfBoundsException.class, () -> PhysicalOps.selectK(arr, 2));
        assertThrows(IndexOutOfBoundsException.class, () -> PhysicalOps.selectK(arr, -3));
    }

    // --- substring ---

    @Test
    void substringToEnd() {
        // "[1:-1]" from paper — skip leading space, take rest
        assertEquals("Barack", PhysicalOps.substring(" Barack", 1, -1, Casing.NONE));
    }

    @Test
    void substringFixedLength() {
        assertEquals("Bara", PhysicalOps.substring("Barack", 0, 4, Casing.NONE));
    }

    @Test
    void substringFullString() {
        assertEquals("Obama", PhysicalOps.substring("Obama", 0, -1, Casing.NONE));
    }

    @Test
    void substringNegativeStart() {
        assertEquals("ack", PhysicalOps.substring("Barack", -3, -1, Casing.NONE));
    }

    @Test
    void substringStartPastEndReturnsEmpty() {
        assertEquals("", PhysicalOps.substring("abc", 10, -1, Casing.NONE));
    }

    @Test
    void substringCasingLower() {
        assertEquals("barack obama", PhysicalOps.substring("Barack Obama", 0, -1, Casing.LOWER));
    }

    @Test
    void substringCasingUpper() {
        assertEquals("BARACK", PhysicalOps.substring("Barack", 0, -1, Casing.UPPER));
    }

    @Test
    void substringCasingTitle() {
        assertEquals("Barack Obama", PhysicalOps.substring("barack obama", 0, -1, Casing.TITLE));
    }

    @Test
    void substringCasingTitlePreservesSpaces() {
        assertEquals("George W. Bush", PhysicalOps.substring("george w. bush", 0, -1, Casing.TITLE));
    }

    // --- concat ---

    @Test
    void concatJoinsTwoStrings() {
        assertEquals("BarackObama", PhysicalOps.concat("Barack", "Obama"));
        assertEquals("Barack Obama", PhysicalOps.concat("Barack", " Obama"));
    }

    // --- constant ---

    @Test
    void constantReturnsValue() {
        assertEquals(" ", PhysicalOps.constant(" "));
        assertEquals("@forsyth.k12.ga.us", PhysicalOps.constant("@forsyth.k12.ga.us"));
    }
}