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
    private static final String COMMENT_SLASHES = "//"; //$NON-NLS-1$
    private static final String SUPPRESSION_MARKER = "BSLLS:"; //$NON-NLS-1$
    private static final String COMMENT_PREFIX = COMMENT_SLASHES + " " + SUPPRESSION_MARKER; //$NON-NLS-1$
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
     * Wraps the line the issue's anchor points at with an off/on pair of {@code ruleKey} suppression
     * comments, each at that line's own indentation, so the BSL Language Server (and SonarQube's community
     * BSL plugin) stops reporting {@code ruleKey} for it.
     *
     * <p>The line is <em>verified before it is edited</em>, never merely trusted: {@code line1Based} is only
     * a hint, and {@link LineAnchor#resolveLine} decides which line - if any - this call may touch. When the
     * recorded number no longer carries {@code lineAnchor}, the line within {@link LineAnchor#SEARCH_RADIUS}
     * that the anchor's context names is edited instead (this is what silently absorbs a server-mode refresh
     * restoring the line numbers of its last analysis, and small local edits above the issue) - and the
     * returned {@link SuppressionResult#line()} says which line that was, because every caller renumbering a
     * model of its own has to renumber around it rather than around {@code line1Based}.
     *
     * <p>Every other reading of the anchor is a refusal, and each has its own outcome: several candidates the
     * context cannot separate ({@link SuppressionOutcome#ANCHOR_AMBIGUOUS}), a neighbourhood too far rewritten
     * to identify the line ({@link SuppressionOutcome#ANCHOR_UNCERTAIN}), the flagged text gone altogether or
     * an unreadable anchor ({@link SuppressionOutcome#ANCHOR_NOT_FOUND}), and an issue that was never
     * fingerprinted at all ({@link SuppressionOutcome#ANCHOR_MISSING}). The last one used to edit
     * {@code line1Based} unverified: for an operation that rewrites the user's source, "no fingerprint" is a
     * reason to stop and let the user refresh, not a reason to trust a number nobody checked.
     *
     * <p>Two further guards make the call a no-op, both against wrapping a comment instead of code:
     * <ul>
     * <li>the resolved line is itself a BSL Language Server suppression comment - inserting would produce an
     * {@code off/off/on/.../on} mess (see {@link #isBslSuppressionComment});</li>
     * <li>the line immediately above it is already the exact off-comment this call would insert - re-running
     * the action on an already-suppressed line does not nest another wrapper around it.</li>
     * </ul>
     *
     * <p>The whole target line is replaced in a single {@link IDocument#replace} call, so no failure can
     * leave the file holding half of a comment pair.
     *
     * <p>The returned result tells a caller that keeps its own in-memory line numbers (see
     * {@link SuppressionLineShift}) whether it may renumber them, and around which line: only a real
     * insertion grew the document by two lines, and shifting after a refusal - or around the wrong line -
     * desynchronizes that caller's model from the file, which is what makes the <em>next</em> suppression
     * wrap the wrong lines.
     *
     * @param document the document to edit, not {@code null}
     * @param line1Based the 1-based line number recorded for the flagged issue
     * @param ruleKey the rule key, bare or {@code bsl:}-prefixed, not {@code null}
     * @param lineAnchor the anchor recorded for the flagged line (see {@link LineAnchor}), not {@code null}
     * @return the outcome and, when the comment pair was written, the 1-based line it was wrapped around,
     *     never {@code null}
     * @throws BadLocationException when reading the resolved line fails, which a resolved anchor makes
     *     unreachable: it is by construction a line of this document
     */
    public static SuppressionResult insert(IDocument document, int line1Based, String ruleKey,
        String lineAnchor) throws BadLocationException
    {
        int target = LineAnchor.resolveLine(document, line1Based, lineAnchor);
        if (!LineAnchor.isResolved(target))
        {
            return SuppressionResult.refused(refusalFor(target));
        }
        int line0 = target - 1;
        if (isBslSuppressionComment(trimmedLineOf(document, line0)))
        {
            return SuppressionResult.refused(SuppressionOutcome.ALREADY_SUPPRESSED);
        }
        String off = offComment(ruleKey);
        if (isAlreadySuppressed(document, line0, off))
        {
            return SuppressionResult.refused(SuppressionOutcome.ALREADY_SUPPRESSED);
        }
        String indentation = leadingWhitespaceOf(document, line0);
        String delimiter = lineDelimiterOf(document, line0);

        IRegion targetRegion = document.getLineInformation(line0);
        String targetText = document.get(targetRegion.getOffset(), targetRegion.getLength());
        String replacement = indentation + off + delimiter
            + targetText + delimiter
            + indentation + onComment(ruleKey);
        document.replace(targetRegion.getOffset(), targetRegion.getLength(), replacement);
        return SuppressionResult.inserted(target);
    }

    /**
     * Translates a {@link LineAnchor#resolveLine} refusal into the outcome the user is told about.
     *
     * @param resolved the value {@link LineAnchor#resolveLine} returned, none of them a line number
     * @return the matching refusal, never {@code null}
     */
    private static SuppressionOutcome refusalFor(int resolved)
    {
        return switch (resolved)
        {
            case LineAnchor.AMBIGUOUS -> SuppressionOutcome.ANCHOR_AMBIGUOUS;
            case LineAnchor.WEAK_EVIDENCE -> SuppressionOutcome.ANCHOR_UNCERTAIN;
            case LineAnchor.NO_ANCHOR -> SuppressionOutcome.ANCHOR_MISSING;
            default -> SuppressionOutcome.ANCHOR_NOT_FOUND;
        };
    }

    /**
     * Tells whether a (trimmed) line of source text is a BSL Language Server suppression comment - either an
     * {@code -off} or an {@code -on} marker, for any rule key.
     *
     * <p>Spelled leniently, because two different jobs depend on recognizing these lines and both are safer
     * when they over-recognize: refusing to wrap a line that is itself a suppression comment, and keeping
     * such lines out of an issue's {@link LineAnchor} context. This plug-in always writes exactly
     * {@code // BSLLS:}, but the engine accepts a hand-written {@code //BSLLS:} just as well.
     *
     * @param trimmedLine a line of source text with leading/trailing whitespace already stripped, not
     *     {@code null}
     * @return {@code true} when {@code trimmedLine} is a comment whose text starts with {@code BSLLS:}
     */
    static boolean isBslSuppressionComment(String trimmedLine)
    {
        return trimmedLine.startsWith(COMMENT_SLASHES)
            && trimmedLine.substring(COMMENT_SLASHES.length()).stripLeading().startsWith(SUPPRESSION_MARKER);
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
