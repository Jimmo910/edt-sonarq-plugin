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

    @Test
    public void toPlainTextDoesNotUnescapeADoubleEscapedAmpersand()
    {
        assertEquals("&lt;", RuleHtml.toPlainText("<p>&amp;lt;</p>"));
    }

    /**
     * Regression coverage for issue #4 follow-up: a naive tag-stripping implementation runs adjacent block
     * elements together with no separator at all ("First paragraph.Second paragraph."), which is unreadable
     * even though no HTML markup is left visible. Consecutive paragraphs must render as separate lines.
     */
    @Test
    public void toPlainTextSeparatesConsecutiveParagraphsWithANewline()
    {
        assertEquals("First paragraph.\nSecond paragraph.",
            RuleHtml.toPlainText("<p>First paragraph.</p><p>Second paragraph.</p>"));
    }

    @Test
    public void toPlainTextRendersListItemsAsOneBulletPerLine()
    {
        assertEquals("- One\n- Two", RuleHtml.toPlainText("<ul><li>One</li><li>Two</li></ul>"));
    }

    @Test
    public void toPlainTextRendersLineBreaksAsNewlines()
    {
        assertEquals("Line one\nLine two", RuleHtml.toPlainText("<p>Line one<br/>Line two</p>"));
    }

    @Test
    public void toPlainTextKeepsAHeadingOnItsOwnLineFromTheFollowingParagraph()
    {
        assertEquals("Noncompliant\nSee the example above.",
            RuleHtml.toPlainText("<h2>Noncompliant</h2><p>See the example above.</p>"));
    }

    @Test
    public void toPlainTextDropsLinkMarkupButKeepsTheLinkText()
    {
        assertEquals("Documentation",
            RuleHtml.toPlainText("<p><a href=\"https://example.org\">Documentation</a></p>"));
    }

    @Test
    public void toPlainTextDecodesNbspAndNumericCharacterReferences()
    {
        assertEquals("a b é é", RuleHtml.toPlainText("<p>a&nbsp;b &#233; &#xe9;</p>"));
    }

    @Test
    public void toPlainTextSeparatesTableCellsAndRows()
    {
        assertEquals("A\tB\n1\t2",
            RuleHtml.toPlainText("<table><tr><th>A</th><th>B</th></tr><tr><td>1</td><td>2</td></tr></table>"));
    }

    @Test
    public void toPlainTextCollapsesLongerRunsOfBlankLinesToOne()
    {
        assertEquals("One\n\nTwo", RuleHtml.toPlainText("<p>One</p><p></p><p></p><p>Two</p>"));
    }
}
