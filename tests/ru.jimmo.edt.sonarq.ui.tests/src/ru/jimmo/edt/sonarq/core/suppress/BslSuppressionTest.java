/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.core.suppress;

import static org.junit.Assert.assertEquals;

import org.eclipse.jface.text.Document;
import org.eclipse.jface.text.IDocument;
import org.junit.Test;

/** Tests for {@link BslSuppression}. */
public class BslSuppressionTest
{
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
        IDocument document = new Document("Процедура П()\n    А = 1;\nКонецПроцедуры\n");

        BslSuppression.insert(document, 2, "MagicNumber");

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

        BslSuppression.insert(document, 1, "bsl:MagicNumber");

        assertEquals("// BSLLS:MagicNumber-off\nА = 1;\n// BSLLS:MagicNumber-on\n", document.get());
    }

    @Test
    public void insertUsesTheDocumentLineDelimiter() throws Exception
    {
        IDocument document = new Document("А = 1;\r\nБ = 2;\r\n");

        BslSuppression.insert(document, 1, "MagicNumber");

        assertEquals("// BSLLS:MagicNumber-off\r\nА = 1;\r\n// BSLLS:MagicNumber-on\r\nБ = 2;\r\n", document.get());
    }

    @Test
    public void insertIsANoOpWhenTheLineIsAlreadySuppressed() throws Exception
    {
        String already = "Процедура П()\n    // BSLLS:MagicNumber-off\n    А = 1;\nКонецПроцедуры\n";
        IDocument document = new Document(already);

        BslSuppression.insert(document, 3, "MagicNumber");

        assertEquals(already, document.get());
    }

    @Test
    public void insertIsANoOpWhenTheTargetLineIsASuppressionComment() throws Exception
    {
        String withComment = "Процедура П()\n    // BSLLS:OtherRule-off\n    А = 1;\nКонецПроцедуры\n";
        IDocument document = new Document(withComment);

        BslSuppression.insert(document, 2, "MagicNumber");

        assertEquals(withComment, document.get());
    }

    @Test
    public void secondInsertOnAStaleSameLineNumberDoesNotCorruptTheFile() throws Exception
    {
        // Reproduces issue #7's follow-up defect: two issues reported on the SAME line, suppressed one
        // after another before the async refresh that would renumber the second one's stale line has run.
        // The first call wraps line 2; the second call, still targeting line 2, would otherwise wrap the
        // freshly inserted off-comment itself (an off/off/on/.../on mess) instead of being a no-op.
        IDocument document = new Document("Процедура П()\n    А = 1;\nКонецПроцедуры\n");
        BslSuppression.insert(document, 2, "MagicNumber");
        String afterFirstInsert = document.get();

        BslSuppression.insert(document, 2, "OtherRule");

        assertEquals(afterFirstInsert, document.get());
    }
}
