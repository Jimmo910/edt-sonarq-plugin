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
}
