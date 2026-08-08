/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.core.suppress;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.eclipse.jface.text.Document;
import org.eclipse.jface.text.DocumentEvent;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IDocumentListener;
import org.junit.Test;

/** Tests for {@link BslSuppression}. */
public class BslSuppressionTest
{
    private static final String SOURCE = "Процедура П()\n    А = 1;\nКонецПроцедуры\n";

    @Test
    public void bareRuleKeyStripsServerPrefix()
    {
        assertEquals("LineLength", BslSuppression.bareRuleKey("bsl:LineLength"));
        assertEquals("LineLength", BslSuppression.bareRuleKey("LineLength"));
    }

    @Test
    public void commentsUseBareKey()
    {
        assertEquals("// BSLLS:MagicNumber-off", BslSuppression.offComment("bsl:MagicNumber"));
        assertEquals("// BSLLS:MagicNumber-on", BslSuppression.onComment("MagicNumber"));
    }

    @Test
    public void insertWrapsLineWithMatchingIndentation() throws Exception
    {
        IDocument document = new Document(SOURCE);

        BslSuppression.insert(document, 2, "MagicNumber", anchorOf(document, 2));

        assertEquals("Процедура П()\n"
            + "    // BSLLS:MagicNumber-off\n"
            + "    А = 1;\n"
            + "    // BSLLS:MagicNumber-on\n"
            + "КонецПроцедуры\n", document.get());
    }

    @Test
    public void insertStripsServerPrefixInComments() throws Exception
    {
        IDocument document = new Document("А = 1;\n");

        BslSuppression.insert(document, 1, "bsl:MagicNumber", anchorOf(document, 1));

        assertEquals("// BSLLS:MagicNumber-off\nА = 1;\n// BSLLS:MagicNumber-on\n", document.get());
    }

    @Test
    public void insertUsesTheDocumentLineDelimiter() throws Exception
    {
        IDocument document = new Document("А = 1;\r\nБ = 2;\r\n");

        BslSuppression.insert(document, 1, "MagicNumber", anchorOf(document, 1));

        assertEquals("// BSLLS:MagicNumber-off\r\nА = 1;\r\n// BSLLS:MagicNumber-on\r\nБ = 2;\r\n", document.get());
    }

    /**
     * The result carries the line that was edited, not only the fact that something was: everything that
     * renumbers a model of its own afterwards has to pivot on it (see {@link SuppressionResult}).
     */
    @Test
    public void insertReportsTheInsertionAndTheLineItEdited() throws Exception
    {
        IDocument document = new Document(SOURCE);

        SuppressionResult result = BslSuppression.insert(document, 2, "MagicNumber", anchorOf(document, 2));

        assertEquals(SuppressionOutcome.INSERTED, result.outcome());
        assertTrue(result.inserted());
        assertEquals(2, result.line());
    }

    /** A refusal carries no line at all, so no caller can renumber around one. */
    @Test
    public void aRefusalReportsNoLine() throws Exception
    {
        IDocument document = new Document(SOURCE);

        SuppressionResult result = BslSuppression.insert(document, 2, "MagicNumber", LineAnchor.of("Б = 2;"));

        assertEquals(SuppressionOutcome.ANCHOR_NOT_FOUND, result.outcome());
        assertEquals(SuppressionResult.NO_LINE, result.line());
    }

    @Test
    public void insertIsANoOpWhenTheLineIsAlreadySuppressed() throws Exception
    {
        String already = "Процедура П()\n    // BSLLS:MagicNumber-off\n    А = 1;\n"
            + "    // BSLLS:MagicNumber-on\nКонецПроцедуры\n";
        IDocument document = new Document(already);

        // The outcome is what tells the caller not to renumber its in-memory issues (see
        // SuppressionDesyncRegressionTest): the document below is unchanged, so the model must be too.
        assertEquals(SuppressionOutcome.ALREADY_SUPPRESSED,
            BslSuppression.insert(document, 3, "MagicNumber", anchorOf(document, 3)).outcome());

        assertEquals(already, document.get());
    }

    @Test
    public void insertIsANoOpWhenTheTargetLineIsASuppressionComment() throws Exception
    {
        String withComment = "Процедура П()\n    // BSLLS:OtherRule-off\n    А = 1;\n"
            + "    // BSLLS:OtherRule-on\nКонецПроцедуры\n";
        IDocument document = new Document(withComment);

        assertEquals(SuppressionOutcome.ALREADY_SUPPRESSED,
            BslSuppression.insert(document, 2, "MagicNumber", LineAnchor.of("// BSLLS:OtherRule-off")).outcome());

        assertEquals(withComment, document.get());
    }

    @Test
    public void secondInsertOfTheSameRuleOnAStaleLineNumberIsANoOp() throws Exception
    {
        // Reproduces issue #7's follow-up defect: two issues of the same rule reported on the SAME line,
        // suppressed one after another before the async refresh that would renumber the second one's stale
        // line has run. The first call wraps line 2; the second call, still targeting line 2, must recognize
        // the wrapper it just wrote instead of nesting another one inside it.
        IDocument document = new Document(SOURCE);
        String anchor = anchorOf(document, 2);
        assertEquals(SuppressionOutcome.INSERTED,
            BslSuppression.insert(document, 2, "MagicNumber", anchor).outcome());
        String afterFirstInsert = document.get();

        assertEquals(SuppressionOutcome.ALREADY_SUPPRESSED,
            BslSuppression.insert(document, 2, "MagicNumber", anchor).outcome());

        assertEquals(afterFirstInsert, document.get());
    }

    /**
     * Two <em>different</em> rules on one line, the second one suppressed before any refresh renumbered it:
     * the recorded number now points at the {@code -off} comment of the first suppression, and only the
     * anchor keeps the second wrapper around the statement instead of around that comment.
     */
    @Test
    public void aSecondRuleOnTheSameLineWrapsTheStatementAgain() throws Exception
    {
        IDocument document = new Document(SOURCE);
        String anchor = anchorOf(document, 2);
        BslSuppression.insert(document, 2, "MagicNumber", anchor);

        SuppressionResult result = BslSuppression.insert(document, 2, "OtherRule", anchor);

        assertEquals(3, result.line());
        assertEquals("Процедура П()\n"
            + "    // BSLLS:MagicNumber-off\n"
            + "    // BSLLS:OtherRule-off\n"
            + "    А = 1;\n"
            + "    // BSLLS:OtherRule-on\n"
            + "    // BSLLS:MagicNumber-on\n"
            + "КонецПроцедуры\n", document.get());
    }

    /** An anchor that still describes the recorded line is simply confirmed, and that line is wrapped. */
    @Test
    public void insertWrapsTheRecordedLineWhenItStillMatchesTheAnchor() throws Exception
    {
        IDocument document = new Document(SOURCE);

        assertEquals(SuppressionOutcome.INSERTED,
            BslSuppression.insert(document, 2, "MagicNumber", anchorOf(document, 2)).outcome());

        assertEquals("Процедура П()\n"
            + "    // BSLLS:MagicNumber-off\n"
            + "    А = 1;\n"
            + "    // BSLLS:MagicNumber-on\n"
            + "КонецПроцедуры\n", document.get());
    }

    /**
     * The scenario the anchor exists for: the line number is stale by two lines - a previous suppression
     * grew the file above this issue, and a server-mode refresh then restored the line numbers SonarQube
     * recorded at its last analysis. Trusting the number wraps ordinary code two lines up; the anchor finds
     * the real line, and the result says which one it was.
     */
    @Test
    public void insertFollowsTheAnchorWhenTheRecordedLineIsStale() throws Exception
    {
        IDocument before = new Document("L1;\nL2;\nL3;\nL4;\nL5;\n");
        String anchor = anchorOf(before, 4);
        IDocument document = new Document("L1;\nL2;\n// BSLLS:R1-off\nL3;\n// BSLLS:R1-on\nL4;\nL5;\n");

        SuppressionResult result = BslSuppression.insert(document, 4, "R2", anchor);

        // Line 4 holds "L3;" now; "L4;" moved down to line 6, and that is what must be wrapped - and
        // reported, so the caller's renumbering pivots on 6 rather than on the stale 4.
        assertEquals(SuppressionOutcome.INSERTED, result.outcome());
        assertEquals(6, result.line());
        assertEquals("L1;\nL2;\n// BSLLS:R1-off\nL3;\n// BSLLS:R1-on\n"
            + "// BSLLS:R2-off\nL4;\n// BSLLS:R2-on\nL5;\n", document.get());
    }

    /** The anchored line may also have moved up - a block above the issue was deleted. */
    @Test
    public void insertFollowsTheAnchorUpwardsToo() throws Exception
    {
        IDocument before = new Document("Процедура П()\ngone1;\ngone2;\nL1;\nL2;\nL3;\n");
        String anchor = anchorOf(before, 4);
        IDocument document = new Document("Процедура П()\nL1;\nL2;\nL3;\n");

        SuppressionResult result = BslSuppression.insert(document, 4, "R1", anchor);

        assertEquals(2, result.line());
        assertEquals("Процедура П()\n// BSLLS:R1-off\nL1;\n// BSLLS:R1-on\nL2;\nL3;\n", document.get());
    }

    /**
     * The safety property: when the anchored line is nowhere near the recorded number, the file is left
     * byte-for-byte untouched instead of some other line being wrapped on a guess.
     */
    @Test
    public void insertRefusesAndWritesNothingWhenTheAnchoredLineIsGone() throws Exception
    {
        IDocument document = new Document(SOURCE);

        assertEquals(SuppressionOutcome.ANCHOR_NOT_FOUND,
            BslSuppression.insert(document, 2, "MagicNumber", LineAnchor.of("Б = 2;")).outcome());

        assertEquals(SOURCE, document.get());
    }

    /**
     * Fail closed: an issue that could never be fingerprinted is refused instead of having its recorded line
     * rewritten unverified, and the refusal names that reason so the user can be told to refresh.
     */
    @Test
    public void insertRefusesAnIssueWithoutAnAnchor() throws Exception
    {
        IDocument document = new Document(SOURCE);

        assertEquals(SuppressionOutcome.ANCHOR_MISSING,
            BslSuppression.insert(document, 2, "MagicNumber", LineAnchor.NONE).outcome());

        assertEquals(SOURCE, document.get());
    }

    /** A corrupted or future-format anchor is refused as well; an unreadable check is not a passed check. */
    @Test
    public void insertRefusesAMalformedAnchor() throws Exception
    {
        IDocument document = new Document(SOURCE);

        assertEquals(SuppressionOutcome.ANCHOR_NOT_FOUND,
            BslSuppression.insert(document, 2, "MagicNumber", "v4:nonsense").outcome());

        assertEquals(SOURCE, document.get());
    }

    /**
     * The ambiguity the context exists for, end to end. Two byte-identical statements sit inside the search
     * window and the recorded number points at the wrong one - the context names the right one, and that is
     * the line that gets wrapped.
     */
    @Test
    public void insertPicksTheRightOneOfTwoIdenticalLinesByItsContext() throws Exception
    {
        IDocument document = new Document("Если А Тогда\n    Возврат;\nКонецЕсли;\n"
            + "Б = 1;\n"
            + "Если В Тогда\n    Возврат;\nКонецЕсли;\n");
        // The second "Возврат;" is on line 6; the analysis that reported it numbered it 2.
        String anchor = anchorOf(document, 6);

        SuppressionResult result = BslSuppression.insert(document, 2, "R1", anchor);

        assertEquals(SuppressionOutcome.INSERTED, result.outcome());
        assertEquals(6, result.line());
        assertEquals("Если А Тогда\n    Возврат;\nКонецЕсли;\n"
            + "Б = 1;\n"
            + "Если В Тогда\n"
            + "    // BSLLS:R1-off\n"
            + "    Возврат;\n"
            + "    // BSLLS:R1-on\n"
            + "КонецЕсли;\n", document.get());
    }

    /**
     * The refusal that replaces the old nearest-match guess: two stretches of code that even the context
     * cannot tell apart leave the file byte-for-byte untouched. Wrapping either of them would be a coin flip
     * on the user's own source, and the outcome tells the caller why the action appeared to do nothing.
     */
    @Test
    public void insertRefusesAndWritesNothingWhenTwoLinesAreIndistinguishable() throws Exception
    {
        // The same two statements ten times over: every "Возврат;" in the middle of the file has exactly the
        // same lines around it as the next one, so no context can name a single line.
        String repeated = "Х = 1;\n    Возврат;\n".repeat(10);
        IDocument document = new Document(repeated);
        String anchor = anchorOf(document, 6);

        assertEquals(SuppressionOutcome.ANCHOR_AMBIGUOUS,
            BslSuppression.insert(document, 6, "R1", anchor).outcome());

        assertEquals(repeated, document.get());
    }

    /**
     * And the refusal for the other half of the rules: the statement is still there, but the code around it
     * was rewritten, so nothing says this is the same line the analysis flagged.
     */
    @Test
    public void insertRefusesWhenTheContextAroundTheLineWasRewritten() throws Exception
    {
        IDocument before = new Document("a;\nb;\nc;\nЦель();\nd;\ne;\nf;\n");
        String anchor = anchorOf(before, 4);
        IDocument document = new Document("x1;\nx2;\nx3;\nЦель();\ny1;\ny2;\ny3;\n");

        assertEquals(SuppressionOutcome.ANCHOR_UNCERTAIN,
            BslSuppression.insert(document, 4, "R1", anchor).outcome());

        assertEquals("x1;\nx2;\nx3;\nЦель();\ny1;\ny2;\ny3;\n", document.get());
    }

    /** A rewritten line is a different line: its old anchor must not match it any more. */
    @Test
    public void insertRefusesWhenTheAnchoredLineWasEdited() throws Exception
    {
        IDocument document = new Document("Процедура П()\n    А = 2;\nКонецПроцедуры\n");

        assertEquals(SuppressionOutcome.ANCHOR_NOT_FOUND,
            BslSuppression.insert(document, 2, "MagicNumber", LineAnchor.of("    А = 1;")).outcome());

        assertEquals("Процедура П()\n    А = 2;\nКонецПроцедуры\n", document.get());
    }

    /** Reindentation is not a content change, so the anchor still matches and the line is still wrapped. */
    @Test
    public void insertAcceptsAnIndentationOnlyDifference() throws Exception
    {
        IDocument document = new Document("Процедура П()\n\t\tА = 1;\nКонецПроцедуры\n");

        assertEquals(SuppressionOutcome.INSERTED,
            BslSuppression.insert(document, 2, "MagicNumber", LineAnchor.of("    А = 1;")).outcome());

        assertEquals("Процедура П()\n"
            + "\t\t// BSLLS:MagicNumber-off\n"
            + "\t\tА = 1;\n"
            + "\t\t// BSLLS:MagicNumber-on\n"
            + "КонецПроцедуры\n", document.get());
    }

    /**
     * Whitespace inside the line, however, is content - it can sit inside a string literal - so a line that
     * differs there is a different line and is refused.
     */
    @Test
    public void insertRefusesWhenOnlyTheInternalWhitespaceDiffers() throws Exception
    {
        String source = "Процедура П()\n    Сообщить(\"a  b\");\nКонецПроцедуры\n";
        IDocument document = new Document(source);

        assertEquals(SuppressionOutcome.ANCHOR_NOT_FOUND,
            BslSuppression.insert(document, 2, "R1", LineAnchor.of("Сообщить(\"a b\");")).outcome());

        assertEquals(source, document.get());
    }

    /**
     * Both comments go in as one document change. Two separate replacements could leave the file holding a
     * lone {@code -off} comment - and therefore the rule disabled to the end of the module - if anything
     * failed in between.
     */
    @Test
    public void insertChangesTheDocumentExactlyOnce() throws Exception
    {
        IDocument document = new Document(SOURCE);
        int[] changes = new int[1];
        document.addDocumentListener(new IDocumentListener()
        {
            @Override
            public void documentAboutToBeChanged(DocumentEvent event)
            {
                // Only completed changes are counted.
            }

            @Override
            public void documentChanged(DocumentEvent event)
            {
                changes[0]++;
            }
        });

        BslSuppression.insert(document, 2, "MagicNumber", anchorOf(document, 2));

        assertEquals(1, changes[0]);
    }

    /**
     * The anchor of one line of a document, as the issue mapping records it.
     *
     * @param document the document to fingerprint
     * @param line1Based the 1-based line number
     * @return the serialized anchor
     */
    private static String anchorOf(IDocument document, int line1Based)
    {
        return LineAnchor.of(document, line1Based);
    }
}
