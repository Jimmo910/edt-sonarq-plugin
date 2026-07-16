/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.ui.views;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Tests for {@link RuleHtml}. */
public class RuleHtmlTest
{
    @Test
    public void wrapProducesCompleteDocument()
    {
        String html = RuleHtml.wrap("<p>Hi</p>");
        assertTrue(html.startsWith("<html>"));
        assertTrue(html.contains("<p>Hi</p>"));
        assertTrue(html.endsWith("</html>"));
    }

    @Test
    public void toPlainTextStripsTagsAndDecodesEntities()
    {
        assertEquals("if a < b & c > d \"quoted\"",
            RuleHtml.toPlainText("<p>if a &lt; b &amp; c &gt; d &quot;quoted&quot;</p>"));
    }
}
