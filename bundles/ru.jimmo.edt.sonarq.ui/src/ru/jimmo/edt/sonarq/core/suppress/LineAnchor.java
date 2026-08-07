/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.core.suppress;

import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IRegion;

/**
 * The fingerprint of the source line an issue was reported on, and the lookup that finds that line again in
 * a document whose line numbering has since moved.
 *
 * <p>Every quick-suppress edits the user's own source, and it used to locate the line to wrap by an integer
 * line number alone. That integer goes stale in ways no amount of renumbering can cover: a server-mode
 * refresh restores the line numbers SonarQube recorded at its last analysis (which know nothing about a local
 * suppression), the user types above the issue, presses Ctrl+Z, or discards a buffer. An anchor turns the
 * number into a hint that can be checked: before writing anything, the caller re-reads the line the number
 * points at and compares it against the anchor recorded when the issue was mapped to the file. Only a match
 * - at the number itself, or on the nearest line within {@link #SEARCH_RADIUS} - is edited; anything else is
 * refused, so a stale number can no longer wrap unrelated code.
 *
 * <p>The anchor is a 64-bit FNV-1a hash of the normalized line text rather than the text itself: it is a
 * fixed 16 characters for a line of any length (an issue snapshot holds up to 10 000 issues, and every anchor
 * is also copied into a workspace marker attribute), it is cheap to compute for a whole file, and it keeps
 * source text out of the marker attributes that any other plug-in can read from the Problems view.
 * Normalization makes the comparison insensitive to reindentation and to trailing whitespace, which are the
 * edits users make without meaning to change a line at all.
 */
public final class LineAnchor
{
    /** The empty anchor: the issue could not be fingerprinted, so its line cannot be verified. */
    public static final String NONE = ""; //$NON-NLS-1$

    /** Returned by {@link #resolveLine} when the anchored line is nowhere near the recorded number. */
    public static final int NOT_FOUND = -1;

    /** How many lines above and below the recorded number {@link #resolveLine} searches. */
    public static final int SEARCH_RADIUS = 25;

    /** The fixed length of an anchor: a 64-bit hash in zero-padded hexadecimal. */
    private static final int ANCHOR_LENGTH = 16;

    private static final long FNV_OFFSET_BASIS = 0xcbf29ce484222325L;

    private static final long FNV_PRIME = 0x100000001b3L;

    private LineAnchor()
    {
    }

    /**
     * Normalizes a line of source text for anchoring: leading and trailing whitespace is stripped and every
     * internal whitespace run is collapsed to a single space.
     *
     * <p>Reindenting a block, or an editor stripping trailing spaces on save, must not make an issue
     * unverifiable - while a genuine change to the line's tokens must.
     *
     * @param lineText the raw line text, not {@code null}
     * @return the normalized text, never {@code null}
     */
    public static String normalize(String lineText)
    {
        StringBuilder normalized = new StringBuilder(lineText.length());
        boolean pendingSpace = false;
        for (int index = 0; index < lineText.length(); index++)
        {
            char character = lineText.charAt(index);
            if (Character.isWhitespace(character))
            {
                pendingSpace = normalized.length() > 0;
                continue;
            }
            if (pendingSpace)
            {
                normalized.append(' ');
                pendingSpace = false;
            }
            normalized.append(character);
        }
        return normalized.toString();
    }

    /**
     * Computes the anchor of a line of source text.
     *
     * @param lineText the raw line text, not {@code null}
     * @return the 16-character hexadecimal anchor, never {@code null} and never {@link #NONE}
     */
    public static String of(String lineText)
    {
        String normalized = normalize(lineText);
        long hash = FNV_OFFSET_BASIS;
        for (int index = 0; index < normalized.length(); index++)
        {
            hash ^= normalized.charAt(index);
            hash *= FNV_PRIME;
        }
        // Hand-padded rather than String.format: no locale, no format parsing, on a path that runs once per
        // issue of a whole snapshot.
        String hexadecimal = Long.toHexString(hash);
        return "0".repeat(ANCHOR_LENGTH - hexadecimal.length()) + hexadecimal; //$NON-NLS-1$
    }

    /**
     * Computes the anchor of a document line.
     *
     * @param document the document to read, not {@code null}
     * @param line1Based the 1-based line number to fingerprint
     * @return the anchor of that line, or {@link #NONE} when the document has no such line
     */
    public static String of(IDocument document, int line1Based)
    {
        String lineText = lineTextOf(document, line1Based);
        return lineText != null ? of(lineText) : NONE;
    }

    /**
     * Tells whether a line of source text still carries the given anchor.
     *
     * @param anchor the recorded anchor, not {@code null}; {@link #NONE} never matches, because an issue that
     *     could not be fingerprinted cannot be verified either
     * @param lineText the current line text, not {@code null}
     * @return {@code true} when the line's own anchor equals {@code anchor}
     */
    public static boolean matches(String anchor, String lineText)
    {
        return !anchor.isEmpty() && anchor.equals(of(lineText));
    }

    /**
     * Finds the line an issue's anchor points at, starting from the line number recorded for it.
     *
     * <p>The three answers, in the order they are tried:
     * <ul>
     * <li>an empty {@code anchor} - the issue could never be fingerprinted (its file was missing when the
     * issues were mapped) - returns {@code line1Based} unchanged, i.e. the behaviour this plug-in had before
     * anchors existed. Nothing is claimed about that line; the caller simply gets no protection it did not
     * have before;</li>
     * <li>the recorded line still matches the anchor - by far the common case - returns it;</li>
     * <li>otherwise the nearest line within {@link #SEARCH_RADIUS} above or below that still matches,
     * preferring the line below on a tie: the drift this repairs most often is a file that grew above the
     * issue (a suppression inserted two comment lines, the user typed a few lines) while the recorded number
     * came from an analysis that predates the growth.</li>
     * </ul>
     *
     * @param document the current content of the file, not {@code null}
     * @param line1Based the 1-based line number recorded for the issue
     * @param anchor the recorded anchor, not {@code null}
     * @return the 1-based line to edit, or {@link #NOT_FOUND} when no line near {@code line1Based} carries
     *     the anchor any more
     */
    public static int resolveLine(IDocument document, int line1Based, String anchor)
    {
        if (anchor.isEmpty())
        {
            return line1Based;
        }
        if (matchesLine(document, line1Based, anchor))
        {
            return line1Based;
        }
        for (int distance = 1; distance <= SEARCH_RADIUS; distance++)
        {
            if (matchesLine(document, line1Based + distance, anchor))
            {
                return line1Based + distance;
            }
            if (matchesLine(document, line1Based - distance, anchor))
            {
                return line1Based - distance;
            }
        }
        return NOT_FOUND;
    }

    /**
     * Tells whether the given document line exists and carries the anchor.
     *
     * @param document the document to read, not {@code null}
     * @param line1Based the 1-based line number, which may well be outside the document
     * @param anchor the recorded anchor, not {@code null}
     * @return {@code true} when the line exists and matches
     */
    private static boolean matchesLine(IDocument document, int line1Based, String anchor)
    {
        String lineText = lineTextOf(document, line1Based);
        return lineText != null && matches(anchor, lineText);
    }

    /**
     * Reads one line of a document by its 1-based number.
     *
     * @param document the document to read, not {@code null}
     * @param line1Based the 1-based line number
     * @return the line text without its delimiter, or {@code null} when the document has no such line
     */
    private static String lineTextOf(IDocument document, int line1Based)
    {
        if (line1Based < 1 || line1Based > document.getNumberOfLines())
        {
            return null;
        }
        try
        {
            IRegion region = document.getLineInformation(line1Based - 1);
            return document.get(region.getOffset(), region.getLength());
        }
        catch (BadLocationException e)
        {
            return null;
        }
    }
}
