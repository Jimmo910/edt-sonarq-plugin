/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.core.localanalysis;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Tests for {@link MarkdownHtml}. */
public class MarkdownHtmlTest
{
    private static final String BSL_LS_FRAGMENT = """
        # Method size

        Methods should not be too long. Split the method into `smaller pieces` for **readability**.

        | Rule | Severity | Tag |
        |------|----------|-----|
        | MethodSize | Major | brain-overload |

        ```bsl
        // if a < b then split it
        // **not bold** in code
        ```

        - Extract helper methods
        - Keep single responsibility

        See [BSL Language Server docs](https://github.com/1c-syntax/bsl-language-server) for details.""";

    @Test
    public void rendersHeadingAsShiftedHtmlTag()
    {
        String html = MarkdownHtml.toHtml(BSL_LS_FRAGMENT);

        assertTrue(html.contains("<h2>Method size</h2>"));
    }

    @Test
    public void rendersPipeTableAsHtmlTable()
    {
        String html = MarkdownHtml.toHtml(BSL_LS_FRAGMENT);

        assertTrue(html.contains("<table>"));
        assertTrue(html.contains("<th>Rule</th>"));
        assertTrue(html.contains("<th>Severity</th>"));
        assertTrue(html.contains("<th>Tag</th>"));
        assertTrue(html.contains("<td>MethodSize</td>"));
        assertTrue(html.contains("<td>Major</td>"));
        assertTrue(html.contains("<td>brain-overload</td>"));
        assertTrue(html.contains("</table>"));
    }

    @Test
    public void rendersFencedCodeBlockEscapedAndUnprocessed()
    {
        String html = MarkdownHtml.toHtml(BSL_LS_FRAGMENT);

        assertTrue(html.contains("<pre><code>"));
        // The raw "<" inside the fence must come out escaped, not as a stray angle bracket.
        assertTrue(html.contains("if a &lt; b then split it"));
        // No inline markup is applied inside a fenced block: "**not bold**" must survive verbatim.
        assertTrue(html.contains("**not bold** in code"));
        assertFalse(html.contains("<b>not bold</b>"));
        assertTrue(html.contains("</code></pre>"));
    }

    @Test
    public void rendersInlineCodeAndBoldInsideParagraph()
    {
        String html = MarkdownHtml.toHtml(BSL_LS_FRAGMENT);

        assertTrue(html.contains("<code>smaller pieces</code>"));
        assertTrue(html.contains("<b>readability</b>"));
    }

    @Test
    public void rendersUnorderedListItems()
    {
        String html = MarkdownHtml.toHtml(BSL_LS_FRAGMENT);

        assertTrue(html.contains("<ul>"));
        assertTrue(html.contains("<li>Extract helper methods</li>"));
        assertTrue(html.contains("<li>Keep single responsibility</li>"));
        assertTrue(html.contains("</ul>"));
    }

    @Test
    public void rendersLinkWithHrefAndText()
    {
        String html = MarkdownHtml.toHtml(BSL_LS_FRAGMENT);

        assertTrue(html.contains(
            "<a href=\"https://github.com/1c-syntax/bsl-language-server\">BSL Language Server docs</a>"));
    }

    @Test
    public void unsafeLinkSchemeRendersTextWithoutAnchor()
    {
        String html = MarkdownHtml.toHtml("Click [here](javascript:alert(1)) now.");

        assertFalse(html.contains("<a "));
        assertFalse(html.contains("javascript:"));
        assertTrue(html.contains("here"));
    }

    @Test
    public void mailtoLinkSchemeIsAllowed()
    {
        String html = MarkdownHtml.toHtml("Write [us](mailto:team@example.com).");

        assertTrue(html.contains("<a href=\"mailto:team@example.com\">us</a>"));
    }

    @Test
    public void atxHeadingsAreShiftedByOneLevel()
    {
        assertEquals("<h2>One</h2>", MarkdownHtml.toHtml("# One"));
        assertEquals("<h3>Two</h3>", MarkdownHtml.toHtml("## Two"));
        assertEquals("<h4>Three</h4>", MarkdownHtml.toHtml("### Three"));
        assertEquals("<h5>Four</h5>", MarkdownHtml.toHtml("#### Four"));
    }

    @Test
    public void starMarkerListItemsAreRendered()
    {
        String html = MarkdownHtml.toHtml("* first\n* second");

        assertEquals("<ul><li>first</li><li>second</li></ul>", html);
    }

    @Test
    public void nullInputYieldsEmptyString()
    {
        assertEquals("", MarkdownHtml.toHtml(null));
    }

    @Test
    public void blankInputYieldsEmptyString()
    {
        assertEquals("", MarkdownHtml.toHtml(""));
        assertEquals("", MarkdownHtml.toHtml("   \n  \n"));
    }

    @Test
    public void tableWithoutSeparatorRowIsTreatedAsPlainText()
    {
        String html = MarkdownHtml.toHtml("| a | b |\n| c | d |");

        assertFalse(html.contains("<table>"));
        assertTrue(html.contains("| a | b |"));
        assertTrue(html.contains("| c | d |"));
    }

    @Test
    public void unterminatedFenceRendersAsCodeUntilEnd()
    {
        String html = MarkdownHtml.toHtml("```bsl\nvar x = 1 < 2;\nreturn x;");

        assertTrue(html.contains("<pre><code>"));
        assertTrue(html.contains("var x = 1 &lt; 2;"));
        assertTrue(html.contains("return x;"));
        assertTrue(html.contains("</code></pre>"));
    }

    @Test
    public void htmlInjectionAttemptIsEscapedNotExecuted()
    {
        String html = MarkdownHtml.toHtml("Ignore this: <script>alert(1)</script> stays text.");

        assertFalse(html.contains("<script>"));
        assertTrue(html.contains("&lt;script&gt;alert(1)&lt;/script&gt;"));
    }
}
