/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.core.suppress;

import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IRegion;
import org.eclipse.jface.text.TextUtilities;

/**
 * Builds and inserts the BSL Language Server inline suppression comments that quick-suppress a single
 * false-positive issue (issue #7): {@code // BSLLS:<rule>-off} on the line above the flagged line, and
 * {@code // BSLLS:<rule>-on} on the line below it. The BSL Language Server, and SonarQube's
 * {@code sonar-bsl-plugin-community} (which embeds the same engine), both honor this pair of comments.
 */
public final class BslSuppression
{
    private static final String SERVER_RULE_PREFIX = "bsl:"; //$NON-NLS-1$
    private static final String COMMENT_PREFIX = "// BSLLS:"; //$NON-NLS-1$
    private static final String OFF_SUFFIX = "-off"; //$NON-NLS-1$
    private static final String ON_SUFFIX = "-on"; //$NON-NLS-1$

    private BslSuppression()
    {
    }

    /**
     * Strips a leading {@code bsl:} server-mode prefix from a rule key.
     *
     * <p>Server-mode issues carry a language-prefixed rule key (e.g. {@code bsl:LineLength}), while
     * local-analysis-mode issues already carry the bare BSL Language Server diagnostic key (e.g.
     * {@code LineLength}) - the suppression comment always needs the bare key, regardless of which mode
     * reported the issue.
     *
     * @param ruleKey the rule key, bare or {@code bsl:}-prefixed, not {@code null}
     * @return the key without a leading {@code bsl:}; {@code ruleKey} unchanged if it has none
     */
    public static String bareRuleKey(String ruleKey)
    {
        return ruleKey.startsWith(SERVER_RULE_PREFIX) ? ruleKey.substring(SERVER_RULE_PREFIX.length()) : ruleKey;
    }

    /**
     * Builds the comment that disables {@code ruleKey} from the line it precedes.
     *
     * @param ruleKey the rule key, bare or {@code bsl:}-prefixed, not {@code null}
     * @return {@code // BSLLS:<bareKey>-off}
     */
    public static String offComment(String ruleKey)
    {
        return COMMENT_PREFIX + bareRuleKey(ruleKey) + OFF_SUFFIX;
    }

    /**
     * Builds the comment that re-enables {@code ruleKey} after the line it follows.
     *
     * @param ruleKey the rule key, bare or {@code bsl:}-prefixed, not {@code null}
     * @return {@code // BSLLS:<bareKey>-on}
     */
    public static String onComment(String ruleKey)
    {
        return COMMENT_PREFIX + bareRuleKey(ruleKey) + ON_SUFFIX;
    }

    /**
     * Wraps the given 1-based line with an off/on pair of {@code ruleKey} suppression comments, each at the
     * flagged line's own indentation, so the BSL Language Server (and SonarQube's community BSL plugin)
     * stops reporting {@code ruleKey} for that line.
     *
     * <p>A no-op in two cases, both guarding against corrupting the file with a nested or wrapped-comment
     * suppression when a caller passes a line number that is stale (see {@link #isBslSuppressionComment}):
     * <ul>
     * <li>{@code line1Based} itself is already a BSL Language Server suppression comment - inserting would
     * wrap the comment instead of code, producing an {@code off/off/on/.../on} mess.</li>
     * <li>the line immediately above {@code line1Based} is already the exact off-comment this call would
     * insert - re-running the action on an already-suppressed line does not nest another wrapper around
     * it.</li>
     * </ul>
     *
     * @param document the document to edit, not {@code null}
     * @param line1Based the 1-based line number of the flagged line
     * @param ruleKey the rule key, bare or {@code bsl:}-prefixed, not {@code null}
     * @throws BadLocationException when {@code line1Based} is out of the document's range
     */
    public static void insert(IDocument document, int line1Based, String ruleKey) throws BadLocationException
    {
        int line0 = line1Based - 1;
        if (isBslSuppressionComment(trimmedLineOf(document, line0)))
        {
            return;
        }
        String off = offComment(ruleKey);
        if (isAlreadySuppressed(document, line0, off))
        {
            return;
        }
        String indentation = leadingWhitespaceOf(document, line0);
        String delimiter = lineDelimiterOf(document, line0);

        int lineOffset = document.getLineOffset(line0);
        document.replace(lineOffset, 0, indentation + off + delimiter);

        // The target line was pushed one line down by the insertion above.
        IRegion targetRegion = document.getLineInformation(line0 + 1);
        int insertOnAt = targetRegion.getOffset() + targetRegion.getLength();
        document.replace(insertOnAt, 0, delimiter + indentation + onComment(ruleKey));
    }

    /**
     * Tells whether a (trimmed) line of source text is a BSL Language Server suppression comment - either an
     * {@code -off} or an {@code -on} marker, for any rule key.
     *
     * @param trimmedLine a line of source text with leading/trailing whitespace already stripped, not
     *     {@code null}
     * @return {@code true} when {@code trimmedLine} starts with the {@code // BSLLS:} prefix
     */
    static boolean isBslSuppressionComment(String trimmedLine)
    {
        return trimmedLine.startsWith(COMMENT_PREFIX);
    }

    private static boolean isAlreadySuppressed(IDocument document, int line0, String off) throws BadLocationException
    {
        if (line0 <= 0)
        {
            return false;
        }
        String aboveTrimmed = trimmedLineOf(document, line0 - 1);
        return isBslSuppressionComment(aboveTrimmed) && aboveTrimmed.equals(off);
    }

    private static String trimmedLineOf(IDocument document, int line0) throws BadLocationException
    {
        IRegion region = document.getLineInformation(line0);
        return document.get(region.getOffset(), region.getLength()).trim();
    }

    private static String leadingWhitespaceOf(IDocument document, int line0) throws BadLocationException
    {
        IRegion region = document.getLineInformation(line0);
        String text = document.get(region.getOffset(), region.getLength());
        int end = 0;
        while (end < text.length() && Character.isWhitespace(text.charAt(end)))
        {
            end++;
        }
        return text.substring(0, end);
    }

    private static String lineDelimiterOf(IDocument document, int line0) throws BadLocationException
    {
        String delimiter = document.getLineDelimiter(line0);
        return delimiter != null ? delimiter : TextUtilities.getDefaultLineDelimiter(document);
    }
}
