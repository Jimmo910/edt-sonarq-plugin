/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.ui.views;

import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Small helpers to render rule descriptions. */
public final class RuleHtml
{
    private static final String EMPTY = ""; //$NON-NLS-1$

    private static final String NEWLINE = "\n"; //$NON-NLS-1$

    private static final String TAB = "\t"; //$NON-NLS-1$

    private static final String BULLET_PREFIX = "\n- "; //$NON-NLS-1$

    // Order matters: these run, in sequence, BEFORE the generic tag-stripping pass below, so the tags they
    // target are replaced with meaningful whitespace instead of disappearing into no separator at all -
    // otherwise adjacent block elements (paragraphs, list items, table cells) would run their text together
    // (e.g. "paragraph oneparagraph two"), which is unreadable even though no HTML markup remains visible.
    private static final Pattern BREAK_TAG = Pattern.compile("(?i)<\\s*br\\s*/?\\s*>"); //$NON-NLS-1$

    private static final Pattern LIST_ITEM_OPEN_TAG = Pattern.compile("(?i)<\\s*li\\b[^>]*>"); //$NON-NLS-1$

    private static final Pattern TABLE_CELL_CLOSE_TAGS = Pattern.compile("(?i)</\\s*(td|th)\\s*>"); //$NON-NLS-1$

    private static final Pattern BLOCK_CLOSE_TAGS =
        Pattern.compile("(?i)</\\s*(p|div|h[1-6]|tr|pre)\\s*>"); //$NON-NLS-1$

    private static final Pattern ANY_TAG = Pattern.compile("<[^>]+>"); //$NON-NLS-1$

    private static final Pattern NUMERIC_ENTITY = Pattern.compile("&#(x[0-9a-fA-F]+|[0-9]+);"); //$NON-NLS-1$

    private static final Pattern TRAILING_LINE_SPACE = Pattern.compile("[ \\t]+\n"); //$NON-NLS-1$

    private static final Pattern BLANK_LINE_RUN = Pattern.compile("\n{3,}"); //$NON-NLS-1$

    private RuleHtml()
    {
    }

    /**
     * Wraps an HTML fragment into a minimal complete document.
     *
     * @param htmlFragment the fragment, not {@code null}
     * @return the document, never {@code null}
     */
    public static String wrap(String htmlFragment)
    {
        return "<html><head><meta charset=\"utf-8\"></head>" //$NON-NLS-1$
            + "<body style=\"font-family:sans-serif;font-size:13px\">" //$NON-NLS-1$
            + htmlFragment + "</body></html>"; //$NON-NLS-1$
    }

    /**
     * Converts an HTML fragment to plain text, for a read-only text rendering of rule HTML where no HTML
     * {@code Browser} is available or preferred (for example on GTK, or when persisting a description into
     * the diagnostics catalog).
     *
     * <p>Block-level tags ({@code <p>}, {@code <div>}, headings, {@code <li>}, table rows/cells,
     * {@code <pre>}) and {@code <br>} are turned into line breaks (and a {@code "- "} bullet for list
     * items) before the remaining markup is stripped, so paragraphs, list items and table cells stay
     * legible instead of being concatenated with no separator. HTML entities - the four predefined XML
     * entities, {@code &nbsp;}, and decimal/hex numeric character references - are then decoded, trailing
     * whitespace a table cell's tab left at a line end is trimmed, and runs of more than one blank line are
     * collapsed to one.
     *
     * @param htmlFragment the fragment, not {@code null}
     * @return the plain text, never {@code null}
     */
    public static String toPlainText(String htmlFragment)
    {
        String withBreaks = BREAK_TAG.matcher(htmlFragment).replaceAll(NEWLINE);
        withBreaks = LIST_ITEM_OPEN_TAG.matcher(withBreaks).replaceAll(BULLET_PREFIX);
        withBreaks = TABLE_CELL_CLOSE_TAGS.matcher(withBreaks).replaceAll(TAB);
        withBreaks = BLOCK_CLOSE_TAGS.matcher(withBreaks).replaceAll(NEWLINE);
        String stripped = ANY_TAG.matcher(withBreaks).replaceAll(EMPTY);
        String trimmedLines = TRAILING_LINE_SPACE.matcher(decodeEntities(stripped)).replaceAll(NEWLINE);
        String collapsed = BLANK_LINE_RUN.matcher(trimmedLines).replaceAll("\n\n"); //$NON-NLS-1$
        return collapsed.trim();
    }

    /**
     * Decodes the HTML entities that occur in rule descriptions: the four predefined XML entities,
     * {@code &nbsp;}, and decimal/hex numeric character references. {@code &amp;} is decoded last, so an
     * already-escaped ampersand (e.g. a literal {@code &lt;} coming through a double-escaped source as
     * {@code &amp;lt;}) is left as the literal text {@code &lt;} instead of being unescaped a second time
     * into {@code <}.
     *
     * @param text the text with tags already stripped, not {@code null}
     * @return the text with entities decoded, never {@code null}
     */
    private static String decodeEntities(String text)
    {
        String decoded = text.replace("&lt;", "<") //$NON-NLS-1$ //$NON-NLS-2$
            .replace("&gt;", ">") //$NON-NLS-1$ //$NON-NLS-2$
            .replace("&quot;", "\"") //$NON-NLS-1$ //$NON-NLS-2$
            .replace("&nbsp;", " "); //$NON-NLS-1$ //$NON-NLS-2$
        decoded = NUMERIC_ENTITY.matcher(decoded).replaceAll(RuleHtml::decodeNumericEntity);
        return decoded.replace("&amp;", "&"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Decodes one decimal ({@code &#39;}) or hex ({@code &#x27;}) numeric character reference match.
     *
     * @param match the regex match for {@link #NUMERIC_ENTITY}, not {@code null}
     * @return the decoded character, or the original matched text if it is not a valid code point
     */
    private static String decodeNumericEntity(MatchResult match)
    {
        String body = match.group(1);
        try
        {
            int codePoint = body.startsWith("x") || body.startsWith("X") //$NON-NLS-1$ //$NON-NLS-2$
                ? Integer.parseInt(body.substring(1), 16)
                : Integer.parseInt(body);
            return Matcher.quoteReplacement(new String(Character.toChars(codePoint)));
        }
        catch (IllegalArgumentException e)
        {
            return Matcher.quoteReplacement(match.group());
        }
    }
}
