/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.core.suppress;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.eclipse.jface.text.Document;
import org.eclipse.jface.text.IDocument;
import org.junit.Test;

/** Tests for {@link LineAnchor}: what counts as the same line, and how far a moved line is followed. */
public class LineAnchorTest
{
    @Test
    public void normalizeStripsTheEdgesAndCollapsesInnerWhitespace()
    {
        assertEquals("А = 1;", LineAnchor.normalize("\t  А   =\t1;  "));
        assertEquals("", LineAnchor.normalize("   \t "));
        assertEquals("", LineAnchor.normalize(""));
    }

    @Test
    public void reindentedLinesShareAnAnchor()
    {
        assertEquals(LineAnchor.of("    А = 1;"), LineAnchor.of("\t\tА  =  1;   "));
    }

    @Test
    public void differentContentGetsADifferentAnchor()
    {
        assertNotEquals(LineAnchor.of("А = 1;"), LineAnchor.of("А = 2;"));
        assertNotEquals(LineAnchor.of("А = 1;"), LineAnchor.of("АБ = 1;"));
    }

    @Test
    public void aLineLevelHashIsAFixedLengthHexadecimalString()
    {
        assertEquals(16, LineAnchor.of("").length());
        assertEquals(16, LineAnchor.of("Процедура ОченьДлинноеИмя(Параметр1, Параметр2, Параметр3)").length());
        assertTrue(LineAnchor.of("А = 1;").matches("[0-9a-f]{16}"));
    }

    /**
     * The serialized shape, which has to survive a round trip through a marker attribute: a version tag and
     * one hash per level, all of it printable ASCII of a fixed length.
     */
    @Test
    public void aDocumentAnchorIsAVersionTagAndOneHashPerLevel()
    {
        String anchor = LineAnchor.of(document("L1;", "L2;", "L3;"), 2);

        assertTrue(anchor, anchor.matches("v2:[0-9a-f]{16}:[0-9a-f]{16}:[0-9a-f]{16}"));
        assertEquals(3, LineAnchor.LEVEL_RADII.length);
    }

    /** The level-0 component is the plain line hash, which is what makes the legacy format readable. */
    @Test
    public void theFirstLevelOfADocumentAnchorIsThePlainLineHash()
    {
        String anchor = LineAnchor.of(document("L1;", "L2;", "L3;"), 2);

        assertEquals(LineAnchor.of("L2;"), anchor.split(":")[1]);
    }

    /** The point of the context levels: two identical lines get two different anchors. */
    @Test
    public void twoIdenticalLinesInDifferentContextsGetDifferentAnchors()
    {
        IDocument document = document("A;", "same;", "B;", "C;", "same;", "D;");

        assertNotEquals(LineAnchor.of(document, 2), LineAnchor.of(document, 5));
    }

    /** An empty anchor means "not verifiable", and must never be reported as a match for anything. */
    @Test
    public void theEmptyAnchorMatchesNothing()
    {
        assertFalse(LineAnchor.matches(LineAnchor.NONE, ""));
        assertFalse(LineAnchor.matches(LineAnchor.NONE, "А = 1;"));
    }

    @Test
    public void matchesIgnoresWhitespaceOnlyDifferences()
    {
        assertTrue(LineAnchor.matches(LineAnchor.of("А = 1;"), "    А  =  1;  "));
        assertFalse(LineAnchor.matches(LineAnchor.of("А = 1;"), "А = 11;"));
    }

    /** {@link LineAnchor#matches} looks at the line level of a multi-level anchor too. */
    @Test
    public void matchesReadsTheLineLevelOfAMultiLevelAnchor()
    {
        String anchor = LineAnchor.of(document("L1;", "L2;", "L3;"), 2);

        assertTrue(LineAnchor.matches(anchor, "  L2;  "));
        assertFalse(LineAnchor.matches(anchor, "L3;"));
    }

    @Test
    public void ofADocumentLineOutsideTheDocumentIsEmpty()
    {
        IDocument document = document("L1;");

        assertEquals(LineAnchor.NONE, LineAnchor.of(document, 99));
        assertEquals(LineAnchor.NONE, LineAnchor.of(document, 0));
    }

    @Test
    public void anEmptyAnchorResolvesToTheRecordedLineUnchecked()
    {
        IDocument document = document("L1;", "L2;", "L3;");

        assertEquals(2, LineAnchor.resolveLine(document, 2, LineAnchor.NONE));
        // Not even a check that the line exists: this is the pre-anchor behaviour, and the caller's
        // BadLocationException handling stays responsible for it.
        assertEquals(99, LineAnchor.resolveLine(document, 99, LineAnchor.NONE));
    }

    @Test
    public void resolveLinePrefersTheRecordedLineWhenItMatches()
    {
        // The same text twice: the recorded line must win over the equally-matching neighbour.
        IDocument document = document("L;", "L;", "L;");

        assertEquals(2, LineAnchor.resolveLine(document, 2, LineAnchor.of("L;")));
    }

    /**
     * The same, one level up: three identical <em>blocks</em>, so even the widest context matches in three
     * places. The recorded line is one of them and must still be taken, rather than the whole call turning
     * into a refusal - a file that did not move at all is the common case.
     */
    @Test
    public void resolveLinePrefersTheRecordedLineOverEquallySpecificMatchesElsewhere()
    {
        IDocument document = document("x;", "x;", "x;", "x;", "x;", "x;", "x;", "x;", "x;", "x;", "x;");

        assertEquals(6, LineAnchor.resolveLine(document, 6, LineAnchor.of(document, 6)));
    }

    /**
     * The defect this rework closes. Two identical lines inside the window and a line number that is stale by
     * two lines: the old lookup walked outwards and wrapped whichever copy it met first, which is a coin flip
     * on a third of all BSL lines. With the context levels, the anchor names one of them.
     */
    @Test
    public void resolveLineTellsIdenticalLinesApartByTheirContext()
    {
        IDocument document = document("Если А Тогда", "Возврат;", "КонецЕсли;", "Б = 1;", "В = 2;",
            "Если Б Тогда", "Возврат;", "КонецЕсли;");
        // The second "Возврат;" - line 7 - fingerprinted while it was still numbered 5.
        String anchor = LineAnchor.of(document, 7);

        assertEquals(7, LineAnchor.resolveLine(document, 5, anchor));
    }

    /**
     * The refusal that replaces the old nearest-match guess: when even the widest context is identical, there
     * is no honest answer, and a wrong edit to the user's source is far worse than a refusal.
     *
     * <p>This case used to return line 5 (the tie was resolved downwards).
     */
    @Test
    public void resolveLineRefusesWhenSeveralLinesInTheWindowAreIdentical()
    {
        IDocument document = document("target;", "filler;", "filler;", "filler;", "target;");

        assertEquals(LineAnchor.AMBIGUOUS, LineAnchor.resolveLine(document, 3, LineAnchor.of("target;")));
        assertFalse(LineAnchor.isResolved(LineAnchor.AMBIGUOUS));
    }

    /**
     * Ambiguity at the widest matching level stops the search: falling through to the line level, where the
     * copies are identical by definition, could only produce the same guess the refusal exists to prevent.
     */
    @Test
    public void resolveLineRefusesWhenTwoWholeBlocksAreIdentical()
    {
        IDocument document = document("a;", "b;", "c;", "d;", "e;", "f;", "g;",
            "a;", "b;", "c;", "d;", "e;", "f;", "g;");
        String anchor = LineAnchor.of(document, 4);

        // Recorded one line off, so the recorded-line preference cannot decide it: both blocks match at every
        // level.
        assertEquals(LineAnchor.AMBIGUOUS, LineAnchor.resolveLine(document, 5, anchor));
    }

    /**
     * The fallback that keeps the widened anchor usable. The lines around the issue changed - here a
     * suppression wrapped the line above it in a comment pair - so neither context level matches any more,
     * while the line itself is untouched and unique. That is a resolution, not a refusal.
     */
    @Test
    public void resolveLineFallsBackToTheLineLevelWhenTheContextChanged()
    {
        IDocument before = document("L1;", "L2;", "L3;", "L4;", "L5;");
        String anchor = LineAnchor.of(before, 4);
        IDocument after = document("L1;", "L2;", "// BSLLS:R1-off", "L3;", "// BSLLS:R1-on", "L4;", "L5;");

        assertEquals(6, LineAnchor.resolveLine(after, 4, anchor));
    }

    /**
     * The middle level earns its place: the seven-line block is gone (an edit rewrote its far edge) and the
     * line level alone is ambiguous - {@code same;} appears twice in the window - but the three-line context
     * names exactly one of them.
     */
    @Test
    public void resolveLineUsesTheThreeLineContextWhenTheSevenLineOneIsGone()
    {
        IDocument before = document("a;", "b;", "same;", "c;", "d;", "e;", "f;", "same;", "g;");
        String anchor = LineAnchor.of(before, 3);
        IDocument after = document("// BSLLS:R1-off", "// BSLLS:R1-on", "a;", "b;", "same;", "c;",
            "rewritten;", "e;", "f;", "same;", "g;");

        assertEquals(5, LineAnchor.resolveLine(after, 3, anchor));
        assertEquals("the line level alone could not have decided this", LineAnchor.AMBIGUOUS,
            LineAnchor.resolveLine(after, 3, LineAnchor.of("same;")));
    }

    /** A bare 16-character hash - everything this plug-in wrote before contexts existed - still resolves. */
    @Test
    public void aLegacyAnchorStillResolves()
    {
        IDocument document = document("L1;", "L2;", "L3;", "target;", "L5;");

        assertEquals(4, LineAnchor.resolveLine(document, 2, LineAnchor.of("target;")));
    }

    /** An anchor in a format this version cannot read is refused, never treated as "no check needed". */
    @Test
    public void anUnreadableAnchorIsRefused()
    {
        IDocument document = document("L1;", "L2;", "L3;");

        assertEquals(LineAnchor.NOT_FOUND, LineAnchor.resolveLine(document, 2, "v9:whatever"));
        assertEquals(LineAnchor.NOT_FOUND, LineAnchor.resolveLine(document, 2, "not-a-hash"));
        assertEquals(LineAnchor.NOT_FOUND, LineAnchor.resolveLine(document, 2, "v2:deadbeefdeadbeef"));
        assertFalse(LineAnchor.matches("v9:whatever", "L2;"));
    }

    @Test
    public void resolveLineFindsAMatchAtTheEdgeOfTheSearchWindow()
    {
        assertEquals(1 + LineAnchor.SEARCH_RADIUS,
            LineAnchor.resolveLine(documentWithTargetAt(1 + LineAnchor.SEARCH_RADIUS), 1,
                LineAnchor.of("target;")));
    }

    @Test
    public void resolveLineGivesUpBeyondTheSearchWindow()
    {
        assertEquals(LineAnchor.NOT_FOUND,
            LineAnchor.resolveLine(documentWithTargetAt(2 + LineAnchor.SEARCH_RADIUS), 1,
                LineAnchor.of("target;")));
    }

    @Test
    public void resolveLineGivesUpWhenTheAnchoredLineIsNowhereInTheFile()
    {
        IDocument document = document("L1;", "L2;", "L3;");

        assertEquals(LineAnchor.NOT_FOUND, LineAnchor.resolveLine(document, 2, LineAnchor.of("gone;")));
    }

    /** A file that shrank leaves the recorded number outside the document; the search must still work. */
    @Test
    public void resolveLineFindsALineEvenWhenTheRecordedNumberIsPastTheEndOfTheFile()
    {
        IDocument document = document("L1;", "target;", "L3;");

        assertEquals(2, LineAnchor.resolveLine(document, 12, LineAnchor.of("target;")));
    }

    /**
     * A block at the very top of a file is padded with "no such line" rather than with empty lines, so it
     * cannot hash equal to the same code preceded by blank lines further down.
     */
    @Test
    public void aBlockAtTheEdgeOfAFileDiffersFromTheSameBlockAmongBlankLines()
    {
        String first = LineAnchor.of(document("a;", "b;", "c;", "d;"), 1);
        String elsewhere = LineAnchor.of(document("", "", "", "a;", "b;", "c;", "d;"), 4);

        assertNotEquals(first, elsewhere);
    }

    /**
     * A document of the given lines, newline-terminated.
     *
     * @param lines the lines, in order
     * @return the document
     */
    private static IDocument document(String... lines)
    {
        StringBuilder text = new StringBuilder();
        for (String line : lines)
        {
            text.append(line).append('\n');
        }
        return new Document(text.toString());
    }

    /**
     * A file of filler lines with a single {@code target;} line at the given 1-based position.
     *
     * @param line1Based where the target line goes
     * @return the document
     */
    private static IDocument documentWithTargetAt(int line1Based)
    {
        StringBuilder text = new StringBuilder();
        for (int line = 1; line <= line1Based; line++)
        {
            text.append(line == line1Based ? "target;" : "filler;").append('\n');
        }
        return new Document(text.toString());
    }
}
