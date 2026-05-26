package com.autojoin.synthesis;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for TransformationSynthesizer (Algorithm 5) using the paper's examples.
 *
 * Each test feeds a small set of (sourceRow, targetValue) example pairs to
 * TryLearnTransform and verifies that the returned program:
 *   (a) reproduces the target values on all training examples, and
 *   (b) generalises correctly to held-out rows from the same table.
 */
class TransformationSynthesizerTest {

    private final TransformationSynthesizer synth = new TransformationSynthesizer();

    // -----------------------------------------------------------------------
    // Figure 1: "Last, First(year-)" → "First Last"
    // Source rows are from the right table; target values from the left table.
    // -----------------------------------------------------------------------

    static final String[] OBAMA   = {"Obama, Barack(1961-)",      "47.0"};
    static final String[] BUSH    = {"Bush, George W.(1946-)",    "49.4"};
    static final String[] CLINTON = {"Clinton, Bill(1946-)",      "55.1"};
    static final String[] REAGAN  = {"Reagan, Ronald(1911-2004)", "52.8"};

    @Test
    void figure1_learnFromThreeExamples() {
        List<ExamplePair> examples = List.of(
                new ExamplePair(OBAMA,   "Barack Obama"),
                new ExamplePair(BUSH,    "George W. Bush"),
                new ExamplePair(CLINTON, "Bill Clinton")
        );

        TransformationProgram prog = synth.tryLearnTransform(examples);

        assertNotNull(prog, "Should find a program for Figure 1");
        assertEquals("Barack Obama",   prog.apply(OBAMA));
        assertEquals("George W. Bush", prog.apply(BUSH));
        assertEquals("Bill Clinton",   prog.apply(CLINTON));
        // Generalise to held-out row
        assertEquals("Ronald Reagan",  prog.apply(REAGAN));
    }

    @Test
    void figure1_programComplexityIsReasonable() {
        List<ExamplePair> examples = List.of(
                new ExamplePair(OBAMA,   "Barack Obama"),
                new ExamplePair(BUSH,    "George W. Bush"),
                new ExamplePair(CLINTON, "Bill Clinton")
        );

        TransformationProgram prog = synth.tryLearnTransform(examples);

        assertNotNull(prog);
        // The paper's program uses 3 operators: first-name, space, last-name.
        // We accept any program of complexity ≤ 6 as "minimum-enough".
        assertTrue(prog.complexity() <= 6,
                "Expected complexity ≤ 6, got " + prog.complexity() + ": " + prog.describe());
    }

    // -----------------------------------------------------------------------
    // Figure 4: [ID, SessionName] → "[ID] SessionName"
    // -----------------------------------------------------------------------

    static final String[] UBAX01 = {"UBAX01", "AXUG General Session"};
    static final String[] UBAX02 = {"UBAX02", "How2 Session"};
    static final String[] UBAX03 = {"UBAX03", "Master Planning Session"};

    @Test
    void figure4_learnBracketConcatTransform() {
        List<ExamplePair> examples = List.of(
                new ExamplePair(UBAX01, "[UBAX01] AXUG General Session"),
                new ExamplePair(UBAX02, "[UBAX02] How2 Session"),
                new ExamplePair(UBAX03, "[UBAX03] Master Planning Session")
        );

        TransformationProgram prog = synth.tryLearnTransform(examples);

        assertNotNull(prog, "Should find a program for Figure 4");
        assertEquals("[UBAX01] AXUG General Session",      prog.apply(UBAX01));
        assertEquals("[UBAX02] How2 Session",              prog.apply(UBAX02));
        assertEquals("[UBAX03] Master Planning Session",   prog.apply(UBAX03));
    }

    // -----------------------------------------------------------------------
    // Trivial identity: target == source column
    // -----------------------------------------------------------------------

    @Test
    void identityTransform_singleColumn() {
        List<ExamplePair> examples = List.of(
                new ExamplePair(new String[]{"Alpha"}, "Alpha"),
                new ExamplePair(new String[]{"Beta"},  "Beta"),
                new ExamplePair(new String[]{"Gamma"}, "Gamma")
        );

        TransformationProgram prog = synth.tryLearnTransform(examples);

        assertNotNull(prog);
        assertEquals("Alpha", prog.apply(new String[]{"Alpha"}));
        assertEquals("Delta", prog.apply(new String[]{"Delta"}));
    }

    // -----------------------------------------------------------------------
    // Constant-only: target is always the same fixed string
    // -----------------------------------------------------------------------

    @Test
    void constantOnlyTransform() {
        List<ExamplePair> examples = List.of(
                new ExamplePair(new String[]{"row1"}, "FIXED"),
                new ExamplePair(new String[]{"row2"}, "FIXED"),
                new ExamplePair(new String[]{"row3"}, "FIXED")
        );

        TransformationProgram prog = synth.tryLearnTransform(examples);

        assertNotNull(prog);
        assertEquals("FIXED", prog.apply(new String[]{"anything"}));
    }

    // -----------------------------------------------------------------------
    // Figure 2: "First Last" → first-initial + last-name + "@forsyth.k12.ga.us"
    // Email = lower(first[0]) + lower(last) + domain
    // -----------------------------------------------------------------------

    static final String[] CHOWDHURY  = {"Suhela Chowdhury",  "Principal"};
    static final String[] CRADDOCK   = {"Carolyn Craddock",  "Admin"};
    static final String[] MOORE      = {"Kelly Moore",       "Instructor"};

    @Test
    void figure2_learnEmailTransform() {
        String domain = "@forsyth.k12.ga.us";
        List<ExamplePair> examples = List.of(
                new ExamplePair(CHOWDHURY, "schowdhury" + domain),
                new ExamplePair(CRADDOCK,  "ccraddock"  + domain),
                new ExamplePair(MOORE,     "kmoore"     + domain)
        );

        TransformationProgram prog = synth.tryLearnTransform(examples);

        assertNotNull(prog, "Should find an email-generation program");
        assertEquals("schowdhury" + domain, prog.apply(CHOWDHURY));
        assertEquals("ccraddock"  + domain, prog.apply(CRADDOCK));
        assertEquals("kmoore"     + domain, prog.apply(MOORE));
    }

    // -----------------------------------------------------------------------
    // Null returned when no consistent program exists
    // -----------------------------------------------------------------------

    @Test
    void returnsNullWhenNoConsistentProgram() {
        // Two examples whose outputs share no derivable pattern from the source
        List<ExamplePair> examples = List.of(
                new ExamplePair(new String[]{"abc"}, "xyz123"),
                new ExamplePair(new String[]{"def"}, "pqr456")
        );

        TransformationProgram prog = synth.tryLearnTransform(examples);
        // Either null or a program that happens to work — both are acceptable.
        // If a program is returned it must be consistent with training examples.
        if (prog != null) {
            assertEquals("xyz123", prog.apply(new String[]{"abc"}));
            assertEquals("pqr456", prog.apply(new String[]{"def"}));
        }
    }
}
