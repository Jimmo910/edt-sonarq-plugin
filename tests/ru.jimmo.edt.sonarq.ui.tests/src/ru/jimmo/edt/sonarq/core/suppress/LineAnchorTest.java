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
    public void anAnchorIsAFixedLengthHexadecimalString()
    {
        assertEquals(16, LineAnchor.of("").length());
        assertEquals(16, LineAnchor.of("Процедура ОченьДлинноеИмя(Параметр1, Параметр2, Параметр3)").length());
        assertTrue(LineAnchor.of("А = 1;").matches("[0-9a-f]{16}"));
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

    @Test
    public void ofADocumentLineReadsThatLine()
    {
        IDocument document = new Document("L1;\nL2;\nL3;\n");

        assertEquals(LineAnchor.of("L2;"), LineAnchor.of(document, 2));
    }

    @Test
    public void ofADocumentLineOutsideTheDocumentIsEmpty()
    {
        IDocument document = new Document("L1;\n");

        assertEquals(LineAnchor.NONE, LineAnchor.of(document, 99));
        assertEquals(LineAnchor.NONE, LineAnchor.of(document, 0));
    }

    @Test
    public void anEmptyAnchorResolvesToTheRecordedLineUnchecked()
    {
        IDocument document = new Document("L1;\nL2;\nL3;\n");

        assertEquals(2, LineAnchor.resolveLine(document, 2, LineAnchor.NONE));
        // Not even a check that the line exists: this is the pre-anchor behaviour, and the caller's
        // BadLocationException handling stays responsible for it.
        assertEquals(99, LineAnchor.resolveLine(document, 99, LineAnchor.NONE));
    }

    @Test
    public void resolveLinePrefersTheRecordedLineWhenItMatches()
    {
        // The same text twice: the recorded line must win over the equally-matching neighbour.
        IDocument document = new Document("L;\nL;\nL;\n");

        assertEquals(2, LineAnchor.resolveLine(document, 2, LineAnchor.of("L;")));
    }

    @Test
    public void resolveLineFindsTheNearestMatchBelowBeforeOneFurtherAbove()
    {
        IDocument document = new Document("target;\nfiller;\nfiller;\nfiller;\ntarget;\n");

        // Recorded line 3: the matches are two lines below (line 5) and two lines above (line 1); a tie is
        // resolved downwards, because the drift this repairs is a file that grew above the issue.
        assertEquals(5, LineAnchor.resolveLine(document, 3, LineAnchor.of("target;")));
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
        IDocument document = new Document("L1;\nL2;\nL3;\n");

        assertEquals(LineAnchor.NOT_FOUND, LineAnchor.resolveLine(document, 2, LineAnchor.of("gone;")));
    }

    /** A file that shrank leaves the recorded number outside the document; the search must still work. */
    @Test
    public void resolveLineFindsALineEvenWhenTheRecordedNumberIsPastTheEndOfTheFile()
    {
        IDocument document = new Document("L1;\ntarget;\nL3;\n");

        assertEquals(2, LineAnchor.resolveLine(document, 12, LineAnchor.of("target;")));
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
