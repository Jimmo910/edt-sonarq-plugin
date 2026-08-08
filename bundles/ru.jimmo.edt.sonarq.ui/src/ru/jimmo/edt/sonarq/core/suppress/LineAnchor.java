/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.core.suppress;

import java.util.HashMap;
import java.util.Map;

import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IRegion;

/**
 * The fingerprint of the source line an issue was reported on - together with the fingerprints of the code
 * around it - and the lookup that finds that line again in a document whose line numbering has since moved.
 *
 * <p>Every quick-suppress edits the user's own source, and it used to locate the line to wrap by an integer
 * line number alone. That integer goes stale in ways no amount of renumbering can cover: a server-mode
 * refresh restores the line numbers SonarQube recorded at its last analysis (which know nothing about a local
 * suppression), the user types above the issue, presses Ctrl+Z, or discards a buffer. An anchor turns the
 * number into a hint that can be checked: before writing anything, the caller re-reads the line the number
 * points at and compares it against the anchor recorded when the issue was mapped to the file. Only a line
 * that carries the anchor <em>and can be told apart from every other line in the window</em> is edited;
 * anything else is refused, so a stale number can no longer wrap unrelated code.
 *
 * <h2>Why one line is not enough</h2>
 *
 * <p>A single line is a poor fingerprint for BSL: measured over 400 real modules (66 785 non-empty lines),
 * <b>33.27 %</b> of all lines have a byte-identical twin within the {@link #SEARCH_RADIUS} lines around them -
 * {@code КонецЕсли;}, {@code Возврат;}, a repeated assignment. A lookup that merely walked outwards and took
 * the first match was therefore guessing on a third of the file. Widening the fingerprint to the surrounding
 * lines collapses that: 11.27 % for three lines, 1.08 % for seven. So an anchor records three hashes -
 * {@link #LEVEL_RADII} - and the lookup uses the widest one that still matches anywhere.
 *
 * <h2>Format</h2>
 *
 * <p>{@code v2:<line>:<3-line context>:<7-line context>}, each component a 64-bit FNV-1a hash of the
 * normalized text in zero-padded hexadecimal. Self-describing, fixed at 53 characters for a line of any
 * length, and therefore still cheap to keep on every issue of a 10 000-issue snapshot and to copy into the
 * workspace marker attribute that carries it between generations. Hashes rather than the text itself also
 * keep source code out of the marker attributes any other plug-in can read from the Problems view.
 *
 * <p>Two older shapes stay valid, because anchors are persisted on markers that outlive an update:
 * <ul>
 * <li>a bare 16-character hexadecimal string - the format this plug-in wrote before contexts existed - is
 * read as a level-0-only anchor and resolved by the line alone;</li>
 * <li>{@link #NONE} means "this issue could never be fingerprinted", and keeps the pre-anchor behaviour of
 * editing the recorded line unverified.</li>
 * </ul>
 * Anything else - a format from a future version, a corrupted attribute - is refused rather than guessed at.
 *
 * <p>Normalization makes every comparison insensitive to reindentation and to trailing whitespace, which are
 * the edits users make without meaning to change a line at all.
 */
public final class LineAnchor
{
    /** The empty anchor: the issue could not be fingerprinted, so its line cannot be verified. */
    public static final String NONE = ""; //$NON-NLS-1$

    /** Returned by {@link #resolveLine} when no line near the recorded number carries the anchor. */
    public static final int NOT_FOUND = -1;

    /**
     * Returned by {@link #resolveLine} when several lines in the window carry the anchor at its widest
     * matching level and none of them is the recorded one - identical blocks of code that no fingerprint of
     * this kind can tell apart. A refusal, not a failure: the caller must write nothing, because one of those
     * lines is the user's and the others are not.
     */
    public static final int AMBIGUOUS = -2;

    /** How many lines above and below the recorded number {@link #resolveLine} searches. */
    public static final int SEARCH_RADIUS = 25;

    /**
     * How many lines above and below the anchored line each level covers, narrowest first: the line alone,
     * the line with its immediate neighbours, and a seven-line block. The position in this array is the level
     * index, and also the position of that level's hash in the serialized anchor.
     */
    public static final int[] LEVEL_RADII = {0, 1, 3};

    /** The prefix marking a multi-level anchor, and the only serialization version this class writes. */
    private static final String VERSION_PREFIX = "v2:"; //$NON-NLS-1$

    /** Separates the per-level hashes of a serialized anchor. */
    private static final String LEVEL_SEPARATOR = ":"; //$NON-NLS-1$

    /** The fixed length of one hash: 64 bits in zero-padded hexadecimal. */
    private static final int HASH_LENGTH = 16;

    /**
     * Stands in a context for a line the document does not have (above its first or past its last line), so
     * that the block at the top of a file cannot hash equal to the same block in the middle of it, and so
     * that a line the file does not have is not read as an empty one.
     *
     * <p>A single space is a sentinel no real line can collide with: {@link #normalize} strips the edges, so
     * a normalized line never begins with whitespace.
     */
    private static final String NO_SUCH_LINE = " "; //$NON-NLS-1$

    private static final char CONTEXT_SEPARATOR = '\n';

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
     * Computes the level-0 anchor of a line of source text: the fingerprint of that line on its own, with no
     * context around it.
     *
     * <p>This is the whole anchor of the legacy format, and the first component of a multi-level one - which
     * is what lets an anchor written by an older version of this plug-in still resolve.
     *
     * @param lineText the raw line text, not {@code null}
     * @return the 16-character hexadecimal hash, never {@code null} and never {@link #NONE}
     */
    public static String of(String lineText)
    {
        return hash(normalize(lineText));
    }

    /**
     * Computes the multi-level anchor of a document line: the line itself, its three-line context and its
     * seven-line context.
     *
     * @param document the document to read, not {@code null}
     * @param line1Based the 1-based line number to fingerprint
     * @return the serialized anchor, or {@link #NONE} when the document has no such line
     */
    public static String of(IDocument document, int line1Based)
    {
        NormalizedLines lines = new NormalizedLines(document);
        if (!lines.exists(line1Based))
        {
            return NONE;
        }
        StringBuilder anchor = new StringBuilder(VERSION_PREFIX);
        for (int level = 0; level < LEVEL_RADII.length; level++)
        {
            if (level > 0)
            {
                anchor.append(LEVEL_SEPARATOR);
            }
            anchor.append(contextHash(lines, line1Based, LEVEL_RADII[level]));
        }
        return anchor.toString();
    }

    /**
     * Tells whether a line of source text still carries the given anchor's own line-level fingerprint - the
     * weakest of the checks {@link #resolveLine} makes, ignoring the anchor's context levels entirely.
     *
     * @param anchor the recorded anchor in any accepted format, not {@code null}; {@link #NONE} never
     *     matches, because an issue that could not be fingerprinted cannot be verified either, and neither
     *     does an anchor this version cannot read
     * @param lineText the current line text, not {@code null}
     * @return {@code true} when the line's own hash equals the anchor's level-0 hash
     */
    public static boolean matches(String anchor, String lineText)
    {
        String[] levels = levelsOf(anchor);
        return levels != null && levels[0] != null && levels[0].equals(of(lineText));
    }

    /**
     * Tells whether {@link #resolveLine} returned a line that may be edited, rather than a refusal.
     *
     * @param resolved the value {@link #resolveLine} returned
     * @return {@code true} unless it is {@link #NOT_FOUND} or {@link #AMBIGUOUS}
     */
    public static boolean isResolved(int resolved)
    {
        return resolved > 0;
    }

    /**
     * Finds the line an issue's anchor points at, starting from the line number recorded for it.
     *
     * <p>The rules, in the order they are applied:
     * <ul>
     * <li>an empty {@code anchor} - the issue could never be fingerprinted (its file was missing when the
     * issues were mapped) - returns {@code line1Based} unchanged, i.e. the behaviour this plug-in had before
     * anchors existed. Nothing is claimed about that line; the caller simply gets no protection it did not
     * have before;</li>
     * <li>an anchor this version cannot read returns {@link #NOT_FOUND}. Refusing is the only safe reading of
     * an attribute whose meaning is unknown;</li>
     * <li>otherwise each level the anchor carries is tried <em>widest context first</em>, and within a level:
     * the recorded line wins if it matches; exactly one other match in the window is taken; several matches
     * return {@link #AMBIGUOUS}, because identical blocks of code cannot be told apart and picking the
     * nearest would be a guess; no match at all falls through to the next narrower level, because the lines
     * <em>around</em> the issue may legitimately have changed - most commonly because a suppression wrapped a
     * neighbouring line in a comment pair;</li>
     * <li>no level matched anywhere: {@link #NOT_FOUND}.</li>
     * </ul>
     *
     * <p>Falling back through the levels is what keeps the anchor usable after ordinary editing; refusing on
     * ambiguity is what keeps it honest. Both are cheap: the window is read and normalized once per call,
     * whatever the number of levels.
     *
     * @param document the current content of the file, not {@code null}
     * @param line1Based the 1-based line number recorded for the issue
     * @param anchor the recorded anchor, not {@code null}
     * @return the 1-based line to edit, or {@link #NOT_FOUND} / {@link #AMBIGUOUS} when there is no single
     *     line this anchor can be said to describe (see {@link #isResolved})
     */
    public static int resolveLine(IDocument document, int line1Based, String anchor)
    {
        if (anchor.isEmpty())
        {
            return line1Based;
        }
        String[] levels = levelsOf(anchor);
        if (levels == null)
        {
            return NOT_FOUND;
        }
        NormalizedLines lines = new NormalizedLines(document);
        for (int level = levels.length - 1; level >= 0; level--)
        {
            if (levels[level] == null)
            {
                continue;
            }
            int resolved = resolveAtLevel(lines, line1Based, levels[level], LEVEL_RADII[level]);
            if (resolved != NOT_FOUND)
            {
                return resolved;
            }
        }
        return NOT_FOUND;
    }

    /**
     * Finds the line carrying one level's hash within the search window.
     *
     * @param lines the document's normalized lines, not {@code null}
     * @param line1Based the 1-based line number recorded for the issue
     * @param levelHash the hash recorded for this level, not {@code null}
     * @param radius how many lines above and below each candidate this level covers
     * @return the single matching 1-based line, {@link #AMBIGUOUS} when several match, or {@link #NOT_FOUND}
     *     when none does - which is the caller's cue to try a narrower level
     */
    private static int resolveAtLevel(NormalizedLines lines, int line1Based, String levelHash, int radius)
    {
        if (levelHash.equals(contextHash(lines, line1Based, radius)))
        {
            // The recorded position wins over an equally specific match elsewhere in the window: it is the
            // one place the analysis actually named, and treating it as one candidate among several would
            // turn the common case - a file that did not move at all - into a refusal.
            return line1Based;
        }
        int found = NOT_FOUND;
        for (int candidate = line1Based - SEARCH_RADIUS; candidate <= line1Based + SEARCH_RADIUS; candidate++)
        {
            if (candidate == line1Based || !levelHash.equals(contextHash(lines, candidate, radius)))
            {
                continue;
            }
            if (found != NOT_FOUND)
            {
                return AMBIGUOUS;
            }
            found = candidate;
        }
        return found;
    }

    /**
     * Hashes the block of lines centred on one line.
     *
     * @param lines the document's normalized lines, not {@code null}
     * @param line1Based the 1-based number of the centre line
     * @param radius how many lines above and below the centre belong to the block; {@code 0} hashes the
     *     centre line alone, and yields exactly what {@link #of(String)} yields for that line's text, which
     *     is what makes a legacy anchor comparable with a level-0 hash
     * @return the hash, or {@code null} when the document has no centre line - such a position is no
     *     candidate at all, and {@code null} equals nothing
     */
    private static String contextHash(NormalizedLines lines, int line1Based, int radius)
    {
        if (!lines.exists(line1Based))
        {
            return null;
        }
        if (radius == 0)
        {
            return hash(lines.at(line1Based));
        }
        StringBuilder block = new StringBuilder();
        for (int offset = -radius; offset <= radius; offset++)
        {
            block.append(lines.at(line1Based + offset)).append(CONTEXT_SEPARATOR);
        }
        return hash(block);
    }

    /**
     * Splits a serialized anchor into its per-level hashes.
     *
     * @param anchor the recorded anchor, not {@code null}
     * @return one entry per level of {@link #LEVEL_RADII}, {@code null} where the anchor carries no hash for
     *     that level; or {@code null} altogether when the anchor is empty or in a format this version cannot
     *     read, which the caller must treat as a refusal rather than as a missing check
     */
    private static String[] levelsOf(String anchor)
    {
        if (isHash(anchor))
        {
            // The pre-context format: the line's own hash, and nothing else to check it against.
            String[] levels = new String[LEVEL_RADII.length];
            levels[0] = anchor;
            return levels;
        }
        if (!anchor.startsWith(VERSION_PREFIX))
        {
            return null;
        }
        String[] levels = anchor.substring(VERSION_PREFIX.length()).split(LEVEL_SEPARATOR, -1);
        if (levels.length != LEVEL_RADII.length)
        {
            return null;
        }
        for (String level : levels)
        {
            if (!isHash(level))
            {
                return null;
            }
        }
        return levels;
    }

    /**
     * Tells whether a string is one serialized hash.
     *
     * @param text the string to check, not {@code null}
     * @return {@code true} for exactly {@link #HASH_LENGTH} lower-case hexadecimal digits
     */
    private static boolean isHash(String text)
    {
        if (text.length() != HASH_LENGTH)
        {
            return false;
        }
        for (int index = 0; index < text.length(); index++)
        {
            char character = text.charAt(index);
            if ((character < '0' || character > '9') && (character < 'a' || character > 'f'))
            {
                return false;
            }
        }
        return true;
    }

    /**
     * The 64-bit FNV-1a hash of a character sequence, in zero-padded hexadecimal.
     *
     * @param text the text to hash, not {@code null}
     * @return the {@link #HASH_LENGTH}-character hash, never {@code null}
     */
    private static String hash(CharSequence text)
    {
        long value = FNV_OFFSET_BASIS;
        for (int index = 0; index < text.length(); index++)
        {
            value ^= text.charAt(index);
            value *= FNV_PRIME;
        }
        // Hand-padded rather than String.format: no locale, no format parsing, on a path that runs once per
        // issue of a whole snapshot.
        String hexadecimal = Long.toHexString(value);
        return "0".repeat(HASH_LENGTH - hexadecimal.length()) + hexadecimal; //$NON-NLS-1$
    }

    /**
     * A document's lines, normalized once and remembered for the duration of one anchor computation or one
     * lookup.
     *
     * <p>A lookup asks for the same line up to seven times - once as the centre of its own block, and once as
     * a neighbour of each of the six blocks that overlap it - at every level. Normalizing on each of those
     * would make a resolve quadratic in the block size for no reason; the marker synchronization runs one
     * resolve per issue of a whole snapshot, which is where that would be felt.
     */
    private static final class NormalizedLines
    {
        private final IDocument document;

        private final Map<Integer, String> normalized = new HashMap<>();

        /**
         * @param document the document to read, not {@code null}
         */
        NormalizedLines(IDocument document)
        {
            this.document = document;
        }

        /**
         * Tells whether the document has the given line at all.
         *
         * @param line1Based the 1-based line number
         * @return {@code true} when the line exists
         */
        boolean exists(int line1Based)
        {
            return !NO_SUCH_LINE.equals(at(line1Based));
        }

        /**
         * The normalized text of one line.
         *
         * @param line1Based the 1-based line number, which may well be outside the document
         * @return the normalized text, or {@link LineAnchor#NO_SUCH_LINE} when the document has no such line
         */
        String at(int line1Based)
        {
            String cached = normalized.get(line1Based);
            if (cached == null)
            {
                String text = lineTextOf(document, line1Based);
                cached = text != null ? normalize(text) : NO_SUCH_LINE;
                normalized.put(line1Based, cached);
            }
            return cached;
        }
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
