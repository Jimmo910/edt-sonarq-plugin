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

import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.Document;
import org.eclipse.jface.text.IDocument;
import org.junit.Test;

/** Tests for {@link LineAnchor}: what counts as the same line, and what evidence it takes to say so. */
public class LineAnchorTest
{
    @Test
    public void normalizeStripsTheEdges()
    {
        assertEquals("А = 1;", LineAnchor.normalize("\t  А = 1;  "));
        assertEquals("", LineAnchor.normalize("   \t "));
        assertEquals("", LineAnchor.normalize(""));
    }

    /**
     * Whitespace <em>inside</em> a line is content: it can sit inside a BSL string literal, where two spaces
     * are not one space. Normalization used to collapse it, which gave two different statements one
     * fingerprint - and a quick-suppress verifies the line it is about to rewrite with exactly that
     * fingerprint.
     */
    @Test
    public void internalWhitespaceIsSignificant()
    {
        assertEquals("Сообщить(\"a  b\");", LineAnchor.normalize("    Сообщить(\"a  b\");   "));
        assertNotEquals(LineAnchor.of("Сообщить(\"a  b\");"), LineAnchor.of("Сообщить(\"a b\");"));
        assertNotEquals(LineAnchor.of("А = 1;"), LineAnchor.of("А  =  1;"));
    }

    @Test
    public void reindentedLinesShareAnAnchor()
    {
        assertEquals(LineAnchor.of("    А = 1;"), LineAnchor.of("\t\tА = 1;   "));
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
     * The serialized shape, which has to survive a round trip through a marker attribute: a version tag, the
     * line's own hash and one hash per recorded context line, all of it printable ASCII of a fixed length.
     */
    @Test
    public void aDocumentAnchorIsAVersionTagAndOneHashPerContextLine()
    {
        String anchor = LineAnchor.of(document("a;", "b;", "c;", "TARGET;", "d;", "e;", "f;"), 4);

        assertTrue(anchor, anchor.matches("v3:[0-9a-f]{16}:([0-9a-f]{16},){2}[0-9a-f]{16}"
            + ":([0-9a-f]{16},){2}[0-9a-f]{16}"));
    }

    /** The first component is the plain line hash, which is what makes the legacy format readable. */
    @Test
    public void theFirstComponentOfADocumentAnchorIsThePlainLineHash()
    {
        String anchor = LineAnchor.of(document("L1;", "L2;", "L3;"), 2);

        assertEquals(LineAnchor.of("L2;"), anchor.split(":")[1]);
    }

    /** A line at the top of a file simply records less context; the anchor is still well-formed. */
    @Test
    public void aLineWithoutContextOnOneSideRecordsAnEmptyComponent()
    {
        String anchor = LineAnchor.of(document("TARGET;", "a;"), 1);

        assertTrue(anchor, anchor.matches("v3:[0-9a-f]{16}::[0-9a-f]{16}"));
    }

    /** The point of the context: two identical lines get two different anchors. */
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
    public void matchesIgnoresIndentationOnly()
    {
        assertTrue(LineAnchor.matches(LineAnchor.of("А = 1;"), "    А = 1;  "));
        assertFalse(LineAnchor.matches(LineAnchor.of("А = 1;"), "А = 11;"));
    }

    /** {@link LineAnchor#matches} reads the line component of a serialized anchor too. */
    @Test
    public void matchesReadsTheLineComponentOfASerializedAnchor()
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

    /**
     * Fail closed. An issue that was never fingerprinted - its module could not be read when the issues were
     * mapped - used to resolve to the recorded line unchecked, i.e. an operation that rewrites the user's
     * source ran on a number nobody had verified. It is a refusal of its own now, so the caller can say why.
     */
    @Test
    public void anEmptyAnchorIsRefusedInsteadOfTrustingTheRecordedLine()
    {
        IDocument document = document("L1;", "L2;", "L3;");

        assertEquals(LineAnchor.NO_ANCHOR, LineAnchor.resolveLine(document, 2, LineAnchor.NONE));
        assertFalse(LineAnchor.isResolved(LineAnchor.NO_ANCHOR));
        assertFalse("an issue with no anchor is not 'findable' either",
            LineAnchor.isFindable(LineAnchor.NO_ANCHOR));
    }

    /** The everyday case: nothing moved, everything around the line is where it was. */
    @Test
    public void resolveLineTakesTheRecordedLineWhenNothingChanged()
    {
        IDocument document = document("a;", "b;", "c;", "TARGET;", "d;", "e;", "f;");

        assertEquals(4, LineAnchor.resolveLine(document, 4, LineAnchor.of(document, 4)));
    }

    /**
     * The guard against reintroducing "the recorded line wins". The number an analysis recorded is not
     * evidence about <em>which</em> of several identical statements it meant - it is where that line was
     * before every edit since - so a duplicate sitting exactly on it may not be preferred to a candidate the
     * context names. Restoring a recorded-line (or nearest-match) preference fails here.
     */
    @Test
    public void recordedDuplicateNeverWinsWithoutContextEvidence()
    {
        // Line 3 and line 9 are both "Возврат;". The issue is on line 9; the recorded number, 3, points at
        // the other copy - which no amount of "it is the line we were told" makes the right one to wrap.
        IDocument document = document("Если А Тогда", "Сообщить(\"А\");", "Возврат;", "КонецЕсли;",
            "Б = 1;", "В = 2;",
            "Если Г Тогда", "Сообщить(\"Г\");", "Возврат;", "КонецЕсли;");
        String anchor = LineAnchor.of(document, 9);

        assertEquals(9, LineAnchor.resolveLine(document, 3, anchor));
    }

    /**
     * The same for the bare line hash: with no context recorded at all, an anchor may only resolve when its
     * line is unique in the window. The recorded number does not break the tie - that is what let a stale
     * number wrap the wrong copy on the third of all BSL lines that have an identical twin nearby.
     */
    @Test
    public void aBareLineHashObeysTheUniquenessRuleAtTheRecordedLineToo()
    {
        IDocument document = document("L;", "L;", "L;");

        assertEquals(LineAnchor.AMBIGUOUS, LineAnchor.resolveLine(document, 2, LineAnchor.of("L;")));
    }

    /** With nothing else in the window carrying it, a bare line hash still resolves. */
    @Test
    public void aBareLineHashResolvesWhenItsLineIsUnique()
    {
        IDocument document = document("L1;", "L2;", "L3;", "target;", "L5;");

        assertEquals(4, LineAnchor.resolveLine(document, 2, LineAnchor.of("target;")));
    }

    /** Two candidates with the same evidence are a refusal, whichever of them the recorded number names. */
    @Test
    public void resolveLineRefusesWhenTheRecordedLineAndAnotherCandidateAreEquallySupported()
    {
        IDocument document = document("a;", "b;", "c;", "TARGET;", "d;", "e;", "f;",
            "a;", "b;", "c;", "TARGET;", "d;", "e;", "f;");
        String anchor = LineAnchor.of(document, 4);

        assertEquals(LineAnchor.AMBIGUOUS, LineAnchor.resolveLine(document, 4, anchor));
        assertEquals(LineAnchor.AMBIGUOUS, LineAnchor.resolveLine(document, 11, anchor));
    }

    /** Two byte-identical seven-line blocks: no fingerprint of this kind can name one of them. */
    @Test
    public void resolveLineRefusesWhenTwoWholeBlocksAreIdentical()
    {
        IDocument document = document("a;", "b;", "c;", "d;", "e;", "f;", "g;",
            "a;", "b;", "c;", "d;", "e;", "f;", "g;");
        String anchor = LineAnchor.of(document, 4);

        assertEquals(LineAnchor.AMBIGUOUS, LineAnchor.resolveLine(document, 5, anchor));
        assertFalse(LineAnchor.isResolved(LineAnchor.AMBIGUOUS));
    }

    /**
     * Partial scoring, which is what keeps the anchor usable while a file is being worked on: one rewritten
     * neighbour costs one point and decides nothing.
     */
    @Test
    public void oneChangedContextLineStillResolves()
    {
        IDocument before = document("a;", "b;", "c;", "TARGET;", "d;", "e;", "f;");
        String anchor = LineAnchor.of(before, 4);
        IDocument after = document("a;", "b;", "rewritten;", "TARGET;", "d;", "e;", "f;");

        assertEquals(4, LineAnchor.resolveLine(after, 4, anchor));
    }

    /** An inserted neighbour shifts the context by one line and must cost no more than that one line. */
    @Test
    public void oneInsertedContextLineStillResolves()
    {
        IDocument before = document("a;", "b;", "c;", "TARGET;", "d;", "e;", "f;");
        String anchor = LineAnchor.of(before, 4);
        IDocument after = document("a;", "b;", "c;", "inserted;", "TARGET;", "d;", "e;", "f;");

        assertEquals(5, LineAnchor.resolveLine(after, 4, anchor));
    }

    /** So must a deleted one. */
    @Test
    public void oneDeletedContextLineStillResolves()
    {
        IDocument before = document("a;", "b;", "c;", "TARGET;", "d;", "e;", "f;");
        String anchor = LineAnchor.of(before, 4);
        IDocument after = document("a;", "b;", "TARGET;", "d;", "e;", "f;");

        assertEquals(3, LineAnchor.resolveLine(after, 4, anchor));
    }

    /**
     * The other end of the same scale, and the reason the evidence has a floor: when the neighbourhood has
     * been rewritten, "the same statement is still somewhere around here" is not a reason to wrap it. The
     * refusal has its own code, because it is a different situation from an ambiguity and from a line that is
     * simply gone.
     */
    @Test
    public void resolveLineRefusesWhenTooMuchOfTheContextChanged()
    {
        IDocument before = document("a;", "b;", "c;", "TARGET;", "d;", "e;", "f;");
        String anchor = LineAnchor.of(before, 4);
        IDocument after = document("x1;", "x2;", "x3;", "TARGET;", "y1;", "y2;", "d;");

        assertEquals(LineAnchor.WEAK_EVIDENCE, LineAnchor.resolveLine(after, 4, anchor));
        assertFalse(LineAnchor.isResolved(LineAnchor.WEAK_EVIDENCE));
    }

    /**
     * The floor applies to an anchor that only ever had one side, too - a line at the very top of a file has
     * no context above it and cannot be asked for any. One surviving neighbour out of three is not enough to
     * call this the same line, and there is no both-sides rule here to refuse it in the floor's place.
     */
    @Test
    public void resolveLineRefusesWhenAOneSidedAnchorHasOnlyOneMatchLeft()
    {
        IDocument before = document("TARGET;", "a;", "b;", "c;");
        String anchor = LineAnchor.of(before, 1);
        IDocument after = document("TARGET;", "a;", "z1;", "z2;");

        assertEquals(LineAnchor.WEAK_EVIDENCE, LineAnchor.resolveLine(after, 1, anchor));
    }

    /** Two of the three, on the other hand, are: the floor is two matches, not all of them. */
    @Test
    public void aOneSidedAnchorResolvesOnTwoMatches()
    {
        IDocument before = document("TARGET;", "a;", "b;", "c;");
        String anchor = LineAnchor.of(before, 1);
        IDocument after = document("TARGET;", "a;", "b;", "z2;");

        assertEquals(1, LineAnchor.resolveLine(after, 1, anchor));
    }

    /**
     * Half a neighbourhood decides nothing either: when the anchor recorded context on both sides, a
     * candidate has to answer on both. A fragment that only agrees above it is what a moved or copied block
     * looks like.
     */
    @Test
    public void resolveLineRefusesWhenOnlyOneSideOfTheContextIsLeft()
    {
        IDocument before = document("a;", "b;", "c;", "TARGET;", "d;", "e;", "f;");
        String anchor = LineAnchor.of(before, 4);
        IDocument after = document("a;", "b;", "c;", "TARGET;", "y1;", "y2;", "y3;");

        assertEquals(LineAnchor.WEAK_EVIDENCE, LineAnchor.resolveLine(after, 4, anchor));
    }

    /**
     * The self-inflicted wound this format exists to avoid: suppressing one issue writes two
     * {@code // BSLLS:} comments around its line, which sit in the context of every issue next to it. They
     * are skipped when the context is collected, so a neighbour's anchor is worth exactly as much after the
     * suppression as before it.
     */
    @Test
    public void ourOwnSuppressionCommentsDoNotInvalidateANeighboursAnchor()
    {
        IDocument before = document("a;", "b;", "c;", "TARGET;", "d;", "e;", "f;");
        String anchor = LineAnchor.of(before, 4);
        // Two neighbouring suppressions, one on each side, exactly as this plug-in writes them.
        IDocument after = document("a;", "b;", "// BSLLS:R1-off", "c;", "// BSLLS:R1-on", "TARGET;",
            "// BSLLS:R2-off", "d;", "// BSLLS:R2-on", "e;", "f;");

        assertEquals(6, LineAnchor.resolveLine(after, 4, anchor));
        assertEquals("the anchor of the same line in the edited file is unchanged", anchor,
            LineAnchor.of(after, 6));
    }

    /** Blank lines carry no evidence and are skipped just as our own comments are. */
    @Test
    public void blankLinesAreNotRecordedAsContext()
    {
        IDocument spaced = document("a;", "", "b;", "", "c;", "", "TARGET;", "", "d;", "", "e;", "", "f;");
        IDocument dense = document("a;", "b;", "c;", "TARGET;", "d;", "e;", "f;");

        assertEquals(LineAnchor.of(dense, 4), LineAnchor.of(spaced, 7));
    }

    /**
     * The scan budget is what bounds that skipping: past {@link LineAnchor#CONTEXT_SCAN} physical lines the
     * anchor simply records less context rather than reaching arbitrarily far for it.
     */
    @Test
    public void theContextScanIsBounded()
    {
        StringBuilder text = new StringBuilder("a;\n");
        text.append("\n".repeat(LineAnchor.CONTEXT_SCAN));
        text.append("TARGET;\n");
        String anchor = LineAnchor.of(new Document(text.toString()), LineAnchor.CONTEXT_SCAN + 2);

        assertTrue(anchor, anchor.matches("v3:[0-9a-f]{16}::"));
    }

    /** A block-hash anchor from the format before this one still resolves, under the same rules. */
    @Test
    public void aBlockHashAnchorStillResolves()
    {
        IDocument document = document("a;", "b;", "c;", "TARGET;", "d;", "e;", "f;");

        assertEquals(4, LineAnchor.resolveLine(document, 2, blockAnchorOf(document, 4)));
    }

    /** And it refuses where the current format would: two identical blocks name nothing. */
    @Test
    public void aBlockHashAnchorRefusesTwoIdenticalBlocks()
    {
        IDocument document = document("a;", "b;", "c;", "TARGET;", "d;", "e;", "f;",
            "a;", "b;", "c;", "TARGET;", "d;", "e;", "f;");

        assertEquals(LineAnchor.AMBIGUOUS, LineAnchor.resolveLine(document, 4, blockAnchorOf(document, 4)));
    }

    /** An anchor in a format this version cannot read is refused, never treated as "no check needed". */
    @Test
    public void anUnreadableAnchorIsRefused()
    {
        IDocument document = document("L1;", "L2;", "L3;");

        assertEquals(LineAnchor.NOT_FOUND, LineAnchor.resolveLine(document, 2, "v9:whatever"));
        assertEquals(LineAnchor.NOT_FOUND, LineAnchor.resolveLine(document, 2, "not-a-hash"));
        assertEquals(LineAnchor.NOT_FOUND, LineAnchor.resolveLine(document, 2, "v2:deadbeefdeadbeef"));
        assertEquals(LineAnchor.NOT_FOUND, LineAnchor.resolveLine(document, 2, "v3:deadbeefdeadbeef"));
        assertEquals(LineAnchor.NOT_FOUND, LineAnchor.resolveLine(document, 2, "v3:deadbeefdeadbeef:zz:"));
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
        assertFalse(LineAnchor.isFindable(LineAnchor.NOT_FOUND));
    }

    /** A file that shrank leaves the recorded number outside the document; the search must still work. */
    @Test
    public void resolveLineFindsALineEvenWhenTheRecordedNumberIsPastTheEndOfTheFile()
    {
        IDocument document = document("L1;", "target;", "L3;");

        assertEquals(2, LineAnchor.resolveLine(document, 12, LineAnchor.of("target;")));
    }

    /**
     * A refusal that still found the anchored code is not a reason to throw the anchor away: the code is
     * there, and re-fingerprinting whatever line the server named would replace a checkable anchor with a
     * confident one nobody verified (see {@code IssueAnchors}).
     */
    @Test
    public void findabilityIsNotTheSameQuestionAsResolvability()
    {
        assertTrue(LineAnchor.isFindable(LineAnchor.AMBIGUOUS));
        assertTrue(LineAnchor.isFindable(LineAnchor.WEAK_EVIDENCE));
        assertTrue(LineAnchor.isFindable(7));
        assertFalse(LineAnchor.isFindable(LineAnchor.NOT_FOUND));
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

    /**
     * The block-hash anchor the previous format recorded for a line: its own hash, then the hash of the
     * three-line and of the seven-line block around it, each block's lines normalized and newline-joined.
     *
     * @param document the document to read
     * @param line1Based the 1-based line to fingerprint
     * @return the serialized {@code v2:} anchor
     */
    private static String blockAnchorOf(IDocument document, int line1Based)
    {
        return "v2:" + LineAnchor.of(lineOf(document, line1Based))
            + ":" + hashOfBlock(document, line1Based, 1)
            + ":" + hashOfBlock(document, line1Based, 3);
    }

    /**
     * The hash of one block of the previous anchor format: its lines normalized, newline-joined, and hashed
     * whole - the trailing newline included.
     *
     * @param document the document to read
     * @param line1Based the 1-based centre line
     * @param radius how many lines above and below belong to the block
     * @return the block hash
     */
    private static String hashOfBlock(IDocument document, int line1Based, int radius)
    {
        StringBuilder block = new StringBuilder();
        for (int offset = -radius; offset <= radius; offset++)
        {
            block.append(normalizedLineOf(document, line1Based + offset)).append('\n');
        }
        return fnv1a(block);
    }

    /**
     * One normalized line of a document, or the "no such line" sentinel the block format padded with.
     *
     * @param document the document to read
     * @param line1Based the 1-based line number
     * @return the normalized line text, or {@code " "} when the document has no such line
     */
    private static String normalizedLineOf(IDocument document, int line1Based)
    {
        return line1Based >= 1 && line1Based <= document.getNumberOfLines()
            ? LineAnchor.normalize(lineOf(document, line1Based))
            : " ";
    }

    /**
     * One raw line of a document, without its delimiter.
     *
     * @param document the document to read
     * @param line1Based the 1-based line number
     * @return the line text
     */
    private static String lineOf(IDocument document, int line1Based)
    {
        try
        {
            return document.get(document.getLineOffset(line1Based - 1),
                document.getLineLength(line1Based - 1)).stripTrailing();
        }
        catch (BadLocationException e)
        {
            throw new AssertionError(e);
        }
    }

    /**
     * The 64-bit FNV-1a hash in zero-padded hexadecimal, spelled out here so that the serialized format is
     * pinned by the test rather than by the implementation it is checking.
     *
     * @param text the text to hash
     * @return the 16-character hash
     */
    private static String fnv1a(CharSequence text)
    {
        long value = 0xcbf29ce484222325L;
        for (int index = 0; index < text.length(); index++)
        {
            value ^= text.charAt(index);
            value *= 0x100000001b3L;
        }
        String hexadecimal = Long.toHexString(value);
        return "0".repeat(16 - hexadecimal.length()) + hexadecimal;
    }
}
