/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.core.localanalysis;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Renders a compact subset of Markdown to HTML, tuned to the rule descriptions the BSL Language
 * Server embeds in SARIF {@code fullDescription} fields.
 *
 * <p>The renderer is intentionally small: it recognizes ATX headings, fenced code blocks, pipe
 * tables, unordered lists and paragraphs at the block level, plus inline {@code code}, {@code
 * **bold**} and {@code [text](url)} links. Everything else is treated as plain paragraph text.
 * All text content is HTML-escaped before any markup is applied, so the renderer never emits
 * unescaped user input and cannot be tricked into producing broken markup by adversarial input —
 * it degrades to plain escaped text instead. Parsing is strictly line-based with linear character
 * scans (no backtracking regular expressions), so pathological input cannot cause catastrophic
 * backtracking.
 */
public final class MarkdownHtml
{
    private static final String EMPTY = ""; //$NON-NLS-1$

    private static final String NEWLINE = "\n"; //$NON-NLS-1$

    private static final String FENCE = "```"; //$NON-NLS-1$

    private static final String PIPE = "|"; //$NON-NLS-1$

    private static final String LIST_MARKER_DASH = "- "; //$NON-NLS-1$

    private static final String LIST_MARKER_STAR = "* "; //$NON-NLS-1$

    private static final char HASH = '#';

    private static final int MAX_HEADING_HASHES = 4;

    private static final int HEADING_LEVEL_SHIFT = 1;

    private MarkdownHtml()
    {
    }

    /**
     * Renders Markdown text as HTML.
     *
     * @param markdown the Markdown source, may be {@code null} or blank
     * @return the rendered HTML, never {@code null}; empty when {@code markdown} is {@code null} or blank
     */
    public static String toHtml(String markdown)
    {
        if (markdown == null || markdown.isBlank())
        {
            return EMPTY;
        }
        List<String> lines = escapedLines(markdown);
        StringBuilder html = new StringBuilder();
        StringBuilder paragraph = new StringBuilder();
        int i = 0;
        while (i < lines.size())
        {
            String trimmed = lines.get(i).trim();
            if (trimmed.isEmpty())
            {
                flushParagraph(html, paragraph);
                i++;
                continue;
            }
            int headingLevel = headingLevel(trimmed);
            if (headingLevel > 0)
            {
                flushParagraph(html, paragraph);
                appendHeading(html, headingLevel, trimmed);
                i++;
                continue;
            }
            if (trimmed.startsWith(FENCE))
            {
                flushParagraph(html, paragraph);
                i = appendCodeBlock(html, lines, i);
                continue;
            }
            if (isTableStart(lines, i))
            {
                flushParagraph(html, paragraph);
                i = appendTable(html, lines, i);
                continue;
            }
            if (isListItem(trimmed))
            {
                flushParagraph(html, paragraph);
                i = appendList(html, lines, i);
                continue;
            }
            appendToParagraph(paragraph, trimmed);
            i++;
        }
        flushParagraph(html, paragraph);
        return html.toString();
    }

    /**
     * Splits the source into lines (normalizing {@code \r\n} and {@code \r}) and HTML-escapes each one,
     * so every later structural check operates on already-escaped text.
     *
     * @param markdown the Markdown source, not {@code null}
     * @return the escaped lines, never {@code null}
     */
    private static List<String> escapedLines(String markdown)
    {
        String normalized = markdown.replace("\r\n", NEWLINE).replace('\r', '\n'); //$NON-NLS-1$
        String[] rawLines = normalized.split(NEWLINE, -1);
        List<String> lines = new ArrayList<>(rawLines.length);
        for (String rawLine : rawLines)
        {
            lines.add(escapeHtml(rawLine));
        }
        return lines;
    }

    /**
     * HTML-escapes {@code &}, {@code <}, {@code >} and {@code "} in text content, in that order so
     * ampersands introduced by escaping are never re-escaped.
     *
     * @param text the raw text, not {@code null}
     * @return the escaped text, never {@code null}
     */
    private static String escapeHtml(String text)
    {
        StringBuilder escaped = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++)
        {
            char c = text.charAt(i);
            switch (c)
            {
                case '&':
                    escaped.append("&amp;"); //$NON-NLS-1$
                    break;
                case '<':
                    escaped.append("&lt;"); //$NON-NLS-1$
                    break;
                case '>':
                    escaped.append("&gt;"); //$NON-NLS-1$
                    break;
                case '"':
                    escaped.append("&quot;"); //$NON-NLS-1$
                    break;
                default:
                    escaped.append(c);
                    break;
            }
        }
        return escaped.toString();
    }

    private static void appendToParagraph(StringBuilder paragraph, String trimmed)
    {
        if (paragraph.length() > 0)
        {
            paragraph.append(' ');
        }
        paragraph.append(applyInline(trimmed));
    }

    private static void flushParagraph(StringBuilder html, StringBuilder paragraph)
    {
        if (paragraph.length() > 0)
        {
            html.append("<p>").append(paragraph).append("</p>"); //$NON-NLS-1$ //$NON-NLS-2$
            paragraph.setLength(0);
        }
    }

    /**
     * Tells whether a trimmed line is an ATX heading ({@code #} through {@code ####} followed by a
     * space), returning its level, or {@code 0} when it is not a heading.
     *
     * @param trimmed the trimmed, already-escaped line, not {@code null}
     * @return the heading level ({@code 1}-{@code 4}), or {@code 0} when {@code trimmed} is not a heading
     */
    private static int headingLevel(String trimmed)
    {
        int hashes = 0;
        while (hashes < trimmed.length() && trimmed.charAt(hashes) == HASH)
        {
            hashes++;
        }
        if (hashes == 0 || hashes > MAX_HEADING_HASHES || hashes >= trimmed.length()
            || trimmed.charAt(hashes) != ' ')
        {
            return 0;
        }
        return hashes;
    }

    private static void appendHeading(StringBuilder html, int level, String trimmed)
    {
        String tag = "h" + (level + HEADING_LEVEL_SHIFT); //$NON-NLS-1$
        String content = trimmed.substring(level).trim();
        html.append('<').append(tag).append('>').append(applyInline(content))
            .append("</").append(tag).append('>'); //$NON-NLS-1$
    }

    /**
     * Consumes a fenced code block starting at {@code start} (a line whose trim starts with the fence
     * marker, optionally followed by a language tag) up to and including its closing fence, rendering
     * the lines in between verbatim (already HTML-escaped, no inline markup applied). An unterminated
     * fence is rendered as code through the end of the input.
     *
     * @param html the output buffer, not {@code null}
     * @param lines the escaped input lines, not {@code null}
     * @param start the index of the opening fence line, must be a valid index into {@code lines}
     * @return the index of the line following the closing fence, or {@code lines.size()} when the fence
     *     was never closed
     */
    private static int appendCodeBlock(StringBuilder html, List<String> lines, int start)
    {
        int i = start + 1;
        List<String> codeLines = new ArrayList<>();
        while (i < lines.size() && !lines.get(i).trim().startsWith(FENCE))
        {
            codeLines.add(lines.get(i));
            i++;
        }
        html.append("<pre><code>") //$NON-NLS-1$
            .append(String.join(NEWLINE, codeLines))
            .append("</code></pre>"); //$NON-NLS-1$
        return i < lines.size() ? i + 1 : i;
    }

    /**
     * Tells whether {@code index} starts a pipe table: a line starting with {@code |} immediately
     * followed by a dash/colon separator line. Without a valid separator the line is not a table and is
     * left for paragraph handling, so a lone {@code |}-containing line renders as plain text.
     *
     * @param lines the escaped input lines, not {@code null}
     * @param index the candidate header line index, must be a valid index into {@code lines}
     * @return {@code true} when a table starts at {@code index}
     */
    private static boolean isTableStart(List<String> lines, int index)
    {
        if (!lines.get(index).trim().startsWith(PIPE) || index + 1 >= lines.size())
        {
            return false;
        }
        return isSeparatorLine(lines.get(index + 1).trim());
    }

    /**
     * Tells whether a trimmed line is a pipe-table separator row: after removing {@code |} characters it
     * contains only {@code -}, {@code :} and spaces, with at least one dash.
     *
     * @param trimmed the trimmed, already-escaped line, not {@code null}
     * @return {@code true} when {@code trimmed} is a valid separator row
     */
    private static boolean isSeparatorLine(String trimmed)
    {
        String withoutPipes = trimmed.replace(PIPE, EMPTY).trim();
        if (withoutPipes.isEmpty())
        {
            return false;
        }
        boolean hasDash = false;
        for (int i = 0; i < withoutPipes.length(); i++)
        {
            char c = withoutPipes.charAt(i);
            if (c == '-')
            {
                hasDash = true;
            }
            else if (c != ':' && c != ' ')
            {
                return false;
            }
        }
        return hasDash;
    }

    /**
     * Consumes a pipe table starting at the header row {@code start}, rendering the header row as
     * {@code <th>} cells and every following {@code |}-prefixed row as {@code <td>} cells, with inline
     * markup applied inside each cell.
     *
     * @param html the output buffer, not {@code null}
     * @param lines the escaped input lines, not {@code null}
     * @param start the index of the header row, must be a valid index into {@code lines}
     * @return the index of the first line after the table
     */
    private static int appendTable(StringBuilder html, List<String> lines, int start)
    {
        html.append("<table>"); //$NON-NLS-1$
        appendTableRow(html, lines.get(start), "th"); //$NON-NLS-1$
        int i = start + 2;
        while (i < lines.size() && lines.get(i).trim().startsWith(PIPE))
        {
            appendTableRow(html, lines.get(i), "td"); //$NON-NLS-1$
            i++;
        }
        html.append("</table>"); //$NON-NLS-1$
        return i;
    }

    private static void appendTableRow(StringBuilder html, String line, String cellTag)
    {
        html.append("<tr>"); //$NON-NLS-1$
        for (String cell : splitRow(line))
        {
            html.append('<').append(cellTag).append('>').append(applyInline(cell))
                .append("</").append(cellTag).append('>'); //$NON-NLS-1$
        }
        html.append("</tr>"); //$NON-NLS-1$
    }

    /**
     * Splits a pipe-table row into trimmed cell contents, dropping the row's leading and trailing pipe.
     *
     * @param line the escaped row line, not {@code null}
     * @return the trimmed cell contents, never {@code null}
     */
    private static List<String> splitRow(String line)
    {
        String trimmedLine = line.trim();
        if (trimmedLine.startsWith(PIPE))
        {
            trimmedLine = trimmedLine.substring(1);
        }
        if (trimmedLine.endsWith(PIPE))
        {
            trimmedLine = trimmedLine.substring(0, trimmedLine.length() - 1);
        }
        String[] parts = trimmedLine.split("\\|", -1); //$NON-NLS-1$
        List<String> cells = new ArrayList<>(parts.length);
        for (String part : parts)
        {
            cells.add(part.trim());
        }
        return cells;
    }

    private static boolean isListItem(String trimmed)
    {
        return trimmed.startsWith(LIST_MARKER_DASH) || trimmed.startsWith(LIST_MARKER_STAR);
    }

    /**
     * Consumes a run of consecutive unordered-list item lines starting at {@code start}.
     *
     * @param html the output buffer, not {@code null}
     * @param lines the escaped input lines, not {@code null}
     * @param start the index of the first list item, must be a valid index into {@code lines}
     * @return the index of the first line after the list
     */
    private static int appendList(StringBuilder html, List<String> lines, int start)
    {
        html.append("<ul>"); //$NON-NLS-1$
        int i = start;
        while (i < lines.size() && isListItem(lines.get(i).trim()))
        {
            String content = lines.get(i).trim().substring(2).trim();
            html.append("<li>").append(applyInline(content)).append("</li>"); //$NON-NLS-1$ //$NON-NLS-2$
            i++;
        }
        html.append("</ul>"); //$NON-NLS-1$
        return i;
    }

    /**
     * Applies inline markup — {@code `code`}, {@code **bold**} and {@code [text](url)} links — to
     * already HTML-escaped text via a single linear left-to-right scan (no regular expressions), so an
     * unmatched marker (e.g. a stray backtick) is left as literal text instead of misparsing the rest of
     * the line.
     *
     * @param text the escaped text to apply inline markup to, not {@code null}
     * @return the text with inline markup rendered as HTML, never {@code null}
     */
    private static String applyInline(String text)
    {
        StringBuilder out = new StringBuilder(text.length());
        int i = 0;
        int len = text.length();
        while (i < len)
        {
            char c = text.charAt(i);
            int consumed = -1;
            if (c == '`')
            {
                consumed = appendCodeSpan(out, text, i);
            }
            else if (c == '*' && i + 1 < len && text.charAt(i + 1) == '*')
            {
                consumed = appendBold(out, text, i);
            }
            else if (c == '[')
            {
                consumed = appendLink(out, text, i);
            }
            if (consumed < 0)
            {
                out.append(c);
                i++;
            }
            else
            {
                i = consumed;
            }
        }
        return out.toString();
    }

    private static int appendCodeSpan(StringBuilder out, String text, int start)
    {
        int end = text.indexOf('`', start + 1);
        if (end < 0)
        {
            return -1;
        }
        out.append("<code>").append(text, start + 1, end).append("</code>"); //$NON-NLS-1$ //$NON-NLS-2$
        return end + 1;
    }

    private static int appendBold(StringBuilder out, String text, int start)
    {
        int end = text.indexOf("**", start + 2); //$NON-NLS-1$
        if (end < 0)
        {
            return -1;
        }
        out.append("<b>").append(text, start + 2, end).append("</b>"); //$NON-NLS-1$ //$NON-NLS-2$
        return end + 2;
    }

    private static int appendLink(StringBuilder out, String text, int start)
    {
        int closeBracket = text.indexOf(']', start + 1);
        if (closeBracket < 0 || closeBracket + 1 >= text.length() || text.charAt(closeBracket + 1) != '(')
        {
            return -1;
        }
        int closeParen = text.indexOf(')', closeBracket + 2);
        if (closeParen < 0)
        {
            return -1;
        }
        String linkText = text.substring(start + 1, closeBracket);
        String url = text.substring(closeBracket + 2, closeParen);
        if (!hasSafeScheme(url))
        {
            // Unsafe scheme (e.g. javascript:) would execute in the Browser widget - keep the text only.
            out.append(linkText);
            return closeParen + 1;
        }
        out.append("<a href=\"").append(url).append("\">") //$NON-NLS-1$ //$NON-NLS-2$
            .append(linkText).append("</a>"); //$NON-NLS-1$
        return closeParen + 1;
    }

    private static boolean hasSafeScheme(String url)
    {
        String lower = url.trim().toLowerCase(Locale.ROOT);
        return lower.startsWith("http://") || lower.startsWith("https://") //$NON-NLS-1$ //$NON-NLS-2$
            || lower.startsWith("mailto:"); //$NON-NLS-1$
    }
}
