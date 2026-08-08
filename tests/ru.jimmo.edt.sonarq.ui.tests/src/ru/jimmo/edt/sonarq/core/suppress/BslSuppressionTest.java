/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.core.suppress;

import static org.junit.Assert.assertEquals;

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

        BslSuppression.insert(document, 2, "MagicNumber", LineAnchor.NONE);

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

        BslSuppression.insert(document, 1, "bsl:MagicNumber", LineAnchor.NONE);

        assertEquals("// BSLLS:MagicNumber-off\nА = 1;\n// BSLLS:MagicNumber-on\n", document.get());
    }

    @Test
    public void insertUsesTheDocumentLineDelimiter() throws Exception
    {
        IDocument document = new Document("А = 1;\r\nБ = 2;\r\n");

        BslSuppression.insert(document, 1, "MagicNumber", LineAnchor.NONE);

        assertEquals("// BSLLS:MagicNumber-off\r\nА = 1;\r\n// BSLLS:MagicNumber-on\r\nБ = 2;\r\n", document.get());
    }

    @Test
    public void insertReportsTheInsertionItPerformed() throws Exception
    {
        IDocument document = new Document(SOURCE);

        assertEquals(SuppressionOutcome.INSERTED,
            BslSuppression.insert(document, 2, "MagicNumber", LineAnchor.NONE));
    }

    @Test
    public void insertIsANoOpWhenTheLineIsAlreadySuppressed() throws Exception
    {
        String already = "Процедура П()\n    // BSLLS:MagicNumber-off\n    А = 1;\nКонецПроцедуры\n";
        IDocument document = new Document(already);

        // The outcome is what tells the caller not to renumber its in-memory issues (see
        // SuppressionDesyncRegressionTest): the document below is unchanged, so the model must be too.
        assertEquals(SuppressionOutcome.ALREADY_SUPPRESSED,
            BslSuppression.insert(document, 3, "MagicNumber", LineAnchor.NONE));

        assertEquals(already, document.get());
    }

    @Test
    public void insertIsANoOpWhenTheTargetLineIsASuppressionComment() throws Exception
    {
        String withComment = "Процедура П()\n    // BSLLS:OtherRule-off\n    А = 1;\nКонецПроцедуры\n";
        IDocument document = new Document(withComment);

        assertEquals(SuppressionOutcome.ALREADY_SUPPRESSED,
            BslSuppression.insert(document, 2, "MagicNumber", LineAnchor.NONE));

        assertEquals(withComment, document.get());
    }

    @Test
    public void secondInsertOnAStaleSameLineNumberDoesNotCorruptTheFile() throws Exception
    {
        // Reproduces issue #7's follow-up defect: two issues reported on the SAME line, suppressed one
        // after another before the async refresh that would renumber the second one's stale line has run.
        // The first call wraps line 2; the second call, still targeting line 2, would otherwise wrap the
        // freshly inserted off-comment itself (an off/off/on/.../on mess) instead of being a no-op.
        IDocument document = new Document(SOURCE);
        assertEquals(SuppressionOutcome.INSERTED,
            BslSuppression.insert(document, 2, "MagicNumber", LineAnchor.NONE));
        String afterFirstInsert = document.get();

        assertEquals(SuppressionOutcome.ALREADY_SUPPRESSED,
            BslSuppression.insert(document, 2, "OtherRule", LineAnchor.NONE));

        assertEquals(afterFirstInsert, document.get());
    }

    /** An anchor that still describes the recorded line is simply confirmed, and that line is wrapped. */
    @Test
    public void insertWrapsTheRecordedLineWhenItStillMatchesTheAnchor() throws Exception
    {
        IDocument document = new Document(SOURCE);

        assertEquals(SuppressionOutcome.INSERTED,
            BslSuppression.insert(document, 2, "MagicNumber", LineAnchor.of("    А = 1;")));

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
     * the real line.
     */
    @Test
    public void insertFollowsTheAnchorWhenTheRecordedLineIsStale() throws Exception
    {
        IDocument document = new Document("L1;\nL2;\n// BSLLS:R1-off\nL3;\n// BSLLS:R1-on\nL4;\nL5;\n");

        assertEquals(SuppressionOutcome.INSERTED,
            BslSuppression.insert(document, 4, "R2", LineAnchor.of("L4;")));

        // Line 4 holds "L3;" now; "L4;" moved down to line 6, and that is what must be wrapped.
        assertEquals("L1;\nL2;\n// BSLLS:R1-off\nL3;\n// BSLLS:R1-on\n"
            + "// BSLLS:R2-off\nL4;\n// BSLLS:R2-on\nL5;\n", document.get());
    }

    /** The anchored line may also have moved up - a block above the issue was deleted. */
    @Test
    public void insertFollowsTheAnchorUpwardsToo() throws Exception
    {
        IDocument document = new Document("L1;\nL2;\nL3;\n");

        assertEquals(SuppressionOutcome.INSERTED, BslSuppression.insert(document, 3, "R1", LineAnchor.of("L1;")));

        assertEquals("// BSLLS:R1-off\nL1;\n// BSLLS:R1-on\nL2;\nL3;\n", document.get());
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
            BslSuppression.insert(document, 2, "MagicNumber", LineAnchor.of("Б = 2;")));

        assertEquals(SOURCE, document.get());
    }

    /**
     * The ambiguity the context levels exist for, end to end. Two byte-identical statements sit inside the
     * search window and the recorded number is stale, so the line alone cannot say which one the issue is on
     * - the seven-line context can, and that is the line that gets wrapped.
     */
    @Test
    public void insertPicksTheRightOneOfTwoIdenticalLinesByItsContext() throws Exception
    {
        IDocument document = new Document("Если А Тогда\n    Возврат;\nКонецЕсли;\n"
            + "Б = 1;\n"
            + "Если В Тогда\n    Возврат;\nКонецЕсли;\n");
        // The second "Возврат;" is on line 6; the analysis that reported it numbered it 2.
        String anchor = LineAnchor.of(document, 6);

        assertEquals(SuppressionOutcome.INSERTED, BslSuppression.insert(document, 2, "R1", anchor));

        assertEquals("Если А Тогда\n    Возврат;\nКонецЕсли;\n"
            + "Б = 1;\n"
            + "Если В Тогда\n"
            + "    // BSLLS:R1-off\n"
            + "    Возврат;\n"
            + "    // BSLLS:R1-on\n"
            + "КонецЕсли;\n", document.get());
    }

    /**
     * The refusal that replaces the old nearest-match guess: two identical lines that even the widest context
     * cannot tell apart leave the file byte-for-byte untouched. Wrapping either of them would be a coin flip
     * on the user's own source, and the outcome tells the caller why the action appeared to do nothing.
     */
    @Test
    public void insertRefusesAndWritesNothingWhenTwoLinesAreIndistinguishable() throws Exception
    {
        // The same two statements ten times over: every "Возврат;" in the middle of the file has exactly the
        // same seven lines around it as the next one, so no context can name a single line.
        String repeated = "Х = 1;\n    Возврат;\n".repeat(10);
        IDocument document = new Document(repeated);
        String anchor = LineAnchor.of(document, 6);

        assertEquals(SuppressionOutcome.ANCHOR_AMBIGUOUS, BslSuppression.insert(document, 5, "R1", anchor));

        assertEquals(repeated, document.get());
    }

    /** A rewritten line is a different line: its old anchor must not match it any more. */
    @Test
    public void insertRefusesWhenTheAnchoredLineWasEdited() throws Exception
    {
        IDocument document = new Document("Процедура П()\n    А = 2;\nКонецПроцедуры\n");

        assertEquals(SuppressionOutcome.ANCHOR_NOT_FOUND,
            BslSuppression.insert(document, 2, "MagicNumber", LineAnchor.of("    А = 1;")));

        assertEquals("Процедура П()\n    А = 2;\nКонецПроцедуры\n", document.get());
    }

    /** Reindentation is not a content change, so the anchor still matches and the line is still wrapped. */
    @Test
    public void insertAcceptsAWhitespaceOnlyDifference() throws Exception
    {
        IDocument document = new Document("Процедура П()\n\t\tА  =   1;\nКонецПроцедуры\n");

        assertEquals(SuppressionOutcome.INSERTED,
            BslSuppression.insert(document, 2, "MagicNumber", LineAnchor.of("    А = 1;")));

        assertEquals("Процедура П()\n"
            + "\t\t// BSLLS:MagicNumber-off\n"
            + "\t\tА  =   1;\n"
            + "\t\t// BSLLS:MagicNumber-on\n"
            + "КонецПроцедуры\n", document.get());
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

        BslSuppression.insert(document, 2, "MagicNumber", LineAnchor.NONE);

        assertEquals(1, changes[0]);
    }
}
