/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.core.suppress;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IRegion;

/**
 * The fingerprint of the source line an issue was reported on - together with the fingerprints of the lines
 * around it - and the lookup that finds that line again in a document whose line numbering has since moved.
 *
 * <p>Every quick-suppress edits the user's own source, and it used to locate the line to wrap by an integer
 * line number alone. That integer goes stale in ways no amount of renumbering can cover: a server-mode
 * refresh restores the line numbers SonarQube recorded at its last analysis (which know nothing about a local
 * suppression), the user types above the issue, presses Ctrl+Z, or discards a buffer. An anchor turns the
 * number into a hint that can be checked: before writing anything, the caller re-reads the window around it
 * and compares it against the anchor recorded when the issue was mapped to the file. Only a line that carries
 * the anchor <em>and can be told apart from every other candidate in the window</em> is edited; everything
 * else is refused, so a stale number can no longer wrap unrelated code.
 *
 * <h2>Why one line is not enough</h2>
 *
 * <p>A single line is a poor fingerprint for BSL: measured over 400 real modules (66 785 non-empty lines),
 * <b>33.3 %</b> of all lines have a byte-identical twin within the {@link #SEARCH_RADIUS} lines around them -
 * {@code КонецЕсли;}, {@code Возврат;}, a repeated assignment. Widening the fingerprint to the code around it
 * collapses that: 11.3 % with one line of context on each side, 2.7 % with two, 1.1 % with three. So an
 * anchor records the flagged line <em>and up to {@link #CONTEXT_LINES} lines on each side of it</em>.
 *
 * <h2>Individual context lines, not a block hash</h2>
 *
 * <p>The context is stored as one hash per line rather than as one hash of the whole block, and that is not a
 * detail: a single edited neighbour invalidates a block hash completely, and this plug-in's own
 * {@code // BSLLS:...} comments are exactly such an edit - suppressing one issue would break the anchors of
 * its neighbours. Hence two rules that go together:
 * <ul>
 * <li>only <em>eligible</em> lines are recorded as context - blank lines and this plug-in's own suppression
 * comments are skipped, at most {@link #CONTEXT_SCAN} physical lines are looked at per side - so a
 * suppression written next door changes nothing about a neighbour's anchor;</li>
 * <li>a candidate is <em>scored</em> against the stored context instead of having to reproduce it exactly:
 * the two sequences are compared order-preservingly (a longest common subsequence over at most three
 * elements), so one inserted, deleted or rewritten neighbour costs one point and shifts nothing else.</li>
 * </ul>
 *
 * <h2>What is accepted</h2>
 *
 * <p>{@link #resolveLine} collects every line in the window whose own text still hashes to the anchor's
 * target hash, scores each of them, and accepts one only when the evidence names it:
 * <ul>
 * <li>the best candidate must carry at least {@link #EVIDENCE_MARGIN} matching context lines (or all of them,
 * when the anchor recorded fewer than that - a line at the top of a file has less context to give);</li>
 * <li>when the anchor recorded context on both sides, the candidate must match at least one line on each
 * side, so half a neighbourhood cannot carry a decision on its own;</li>
 * <li>and it must be ahead of the runner-up by at least {@link #EVIDENCE_MARGIN} points.</li>
 * </ul>
 * Nothing here prefers the recorded line: a recorded number that lands on one of several identical statements
 * is not evidence about which of them the analysis meant, and treating it as such is what let a stale number
 * wrap the wrong copy. Failing any of the three rules is a refusal with its own reason
 * ({@link #AMBIGUOUS}, {@link #WEAK_EVIDENCE}), never a nearest-match guess.
 *
 * <h2>Format</h2>
 *
 * <p>{@code v3:<line>:<up to 3 hashes above, nearest first>:<up to 3 hashes below, nearest first>}, the
 * context hashes comma-separated, each component a 64-bit FNV-1a hash of the normalized text in zero-padded
 * hexadecimal. Self-describing, at most 123 characters for a line of any length, and therefore still cheap to
 * keep on every issue of a 10 000-issue snapshot and to copy into the workspace marker attribute that carries
 * it between generations. Hashes rather than the text itself also keep source code out of the marker
 * attributes any other plug-in can read from the Problems view.
 *
 * <p>Two older shapes stay readable, because anchors are persisted on markers that outlive an update - and
 * both obey exactly the same refusal rules, so an old anchor buys no licence to guess:
 * <ul>
 * <li>a bare 16-character hexadecimal string - the format this plug-in wrote before contexts existed - is the
 * target hash with no evidence at all, so it resolves only when it matches a single line in the window;</li>
 * <li>{@code v2:<line>:<3-line block>:<7-line block>} - the block-hash format - keeps its two block hashes as
 * one point of evidence each.</li>
 * </ul>
 * {@link #NONE} - "this issue could never be fingerprinted" - is {@link #NO_ANCHOR}: an operation that
 * rewrites the user's source may not run unverified, so the caller has to report it and let the user refresh.
 * Anything else - a format from a future version, a corrupted attribute - is {@link #NOT_FOUND}.
 *
 * <p>Normalization makes every comparison insensitive to indentation and to trailing whitespace, which are
 * the edits users make without meaning to change a line at all. It deliberately stops there: whitespace
 * <em>inside</em> a line can sit inside a BSL string literal, where it is content like any other character.
 */
public final class LineAnchor
{
    /** The empty anchor: the issue could not be fingerprinted, so its line cannot be verified. */
    public static final String NONE = ""; //$NON-NLS-1$

    /**
     * Returned by {@link #resolveLine} when no line near the recorded number carries the anchor's target
     * hash, and when the anchor is in a format this version cannot read.
     */
    public static final int NOT_FOUND = -1;

    /**
     * Returned by {@link #resolveLine} when several lines in the window carry the anchor and the context
     * cannot put one of them clearly ahead of the others - identical code that no fingerprint of this kind
     * can tell apart. A refusal, not a failure: the caller must write nothing, because one of those lines is
     * the user's and the others are not.
     */
    public static final int AMBIGUOUS = -2;

    /**
     * Returned by {@link #resolveLine} when the flagged line was found but too little of the code recorded
     * around it is still there to call it the same line - the neighbourhood was rewritten. Refusing keeps a
     * heavily edited file from being wrapped on the strength of one repeated statement.
     */
    public static final int WEAK_EVIDENCE = -3;

    /**
     * Returned by {@link #resolveLine} for {@link #NONE}: the issue carries no anchor at all, which is not a
     * licence to edit the recorded line unverified but a refusal of its own, so the user can be told to
     * refresh the issues and try again.
     */
    public static final int NO_ANCHOR = -4;

    /** How many lines above and below the recorded number {@link #resolveLine} searches. */
    public static final int SEARCH_RADIUS = 25;

    /** How many context lines an anchor records on each side of the flagged line. */
    public static final int CONTEXT_LINES = 3;

    /**
     * How many physical lines are looked at per side while collecting those context lines. The budget is what
     * keeps the skipping of blank lines and of this plug-in's own suppression comments bounded: past it, the
     * anchor simply records less context and the rules below adapt.
     */
    public static final int CONTEXT_SCAN = 8;

    /**
     * How much context evidence a candidate needs, both in absolute terms and over the runner-up, before it
     * may be edited. Two matching neighbours is the point at which a coincidence stops being likely.
     */
    public static final int EVIDENCE_MARGIN = 2;

    /** The prefix marking a per-line-context anchor, and the only serialization version this class writes. */
    private static final String VERSION_PREFIX = "v3:"; //$NON-NLS-1$

    /** The prefix of the block-hash format this class still reads. */
    private static final String BLOCK_VERSION_PREFIX = "v2:"; //$NON-NLS-1$

    /**
     * The radii of the two context blocks a {@link #BLOCK_VERSION_PREFIX} anchor carries after its line hash,
     * in the order they are serialized.
     */
    private static final int[] BLOCK_RADII = {1, 3};

    /** How many components a {@link #BLOCK_VERSION_PREFIX} anchor has: the line hash and its two blocks. */
    private static final int BLOCK_COMPONENTS = 3;

    /** How many components a {@link #VERSION_PREFIX} anchor has: the line hash, the lines above, below. */
    private static final int COMPONENTS = 3;

    /** Separates the components of a serialized anchor. */
    private static final String COMPONENT_SEPARATOR = ":"; //$NON-NLS-1$

    /** Separates the individual context hashes inside one component. */
    private static final String CONTEXT_SEPARATOR = ","; //$NON-NLS-1$

    /** The fixed length of one hash: 64 bits in zero-padded hexadecimal. */
    private static final int HASH_LENGTH = 16;

    /**
     * Stands in for a line the document does not have (above its first or past its last line), so that a line
     * the file does not have is not read as an empty one.
     *
     * <p>A single space is a sentinel no real line can collide with: {@link #normalize} strips the edges, so
     * a normalized line never begins with whitespace.
     */
    private static final String NO_SUCH_LINE = " "; //$NON-NLS-1$

    /** Joins the lines of a {@link #BLOCK_VERSION_PREFIX} block before hashing it. */
    private static final char BLOCK_LINE_SEPARATOR = '\n';

    private static final long FNV_OFFSET_BASIS = 0xcbf29ce484222325L;

    private static final long FNV_PRIME = 0x100000001b3L;

    private LineAnchor()
    {
    }

    /**
     * Normalizes a line of source text for anchoring: leading and trailing whitespace is stripped, and
     * nothing else is touched.
     *
     * <p>Reindenting a block, or an editor stripping trailing spaces on save, must not make an issue
     * unverifiable - while a genuine change to the line's characters must. Whitespace inside the line is such
     * a character: {@code Сообщить("a  b")} and {@code Сообщить("a b")} are two different statements, and
     * collapsing runs of spaces would have given them one fingerprint.
     *
     * @param lineText the raw line text, not {@code null}
     * @return the normalized text, never {@code null}
     */
    public static String normalize(String lineText)
    {
        return lineText.strip();
    }

    /**
     * Computes the target hash of a line of source text: the fingerprint of that line on its own, with no
     * context around it.
     *
     * <p>This is the whole of the legacy anchor format, and the first component of every newer one - which is
     * what lets an anchor written by an older version of this plug-in still be read.
     *
     * @param lineText the raw line text, not {@code null}
     * @return the 16-character hexadecimal hash, never {@code null} and never {@link #NONE}
     */
    public static String of(String lineText)
    {
        return hash(normalize(lineText));
    }

    /**
     * Computes the anchor of a document line: the line itself plus the eligible lines around it.
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
        return VERSION_PREFIX + hash(lines.at(line1Based))
            + COMPONENT_SEPARATOR + String.join(CONTEXT_SEPARATOR, contextHashes(lines, line1Based, -1))
            + COMPONENT_SEPARATOR + String.join(CONTEXT_SEPARATOR, contextHashes(lines, line1Based, 1));
    }

    /**
     * Tells whether a line of source text still carries the given anchor's target hash - the weakest of the
     * checks {@link #resolveLine} makes, ignoring the anchor's context entirely.
     *
     * @param anchor the recorded anchor in any accepted format, not {@code null}; {@link #NONE} never
     *     matches, because an issue that could not be fingerprinted cannot be verified either, and neither
     *     does an anchor this version cannot read
     * @param lineText the current line text, not {@code null}
     * @return {@code true} when the line's own hash equals the anchor's target hash
     */
    public static boolean matches(String anchor, String lineText)
    {
        Anchor parsed = parse(anchor);
        return parsed != null && parsed.targetHash.equals(of(lineText));
    }

    /**
     * Tells whether {@link #resolveLine} returned a line that may be edited, rather than a refusal.
     *
     * @param resolved the value {@link #resolveLine} returned
     * @return {@code true} only for a positive line number
     */
    public static boolean isResolved(int resolved)
    {
        return resolved > 0;
    }

    /**
     * Tells whether the anchored code is still <em>somewhere</em> near the recorded line, even if no single
     * line may be edited on the strength of it.
     *
     * <p>Not the same question as {@link #isResolved}, and asked by a different caller: the anchoring of a
     * fresh generation of issues (see {@code ru.jimmo.edt.sonarq.ui.resources.IssueAnchors}) keeps a carried
     * anchor while this holds, and re-fingerprints the reported line only once the anchored code is really
     * gone. A refusal to <em>edit</em> - ambiguity, or a rewritten neighbourhood - is emphatically not a
     * reason to replace a checkable anchor with a fresh fingerprint of whatever line the server named, which
     * would turn a safe refusal into a confident edit of a line nobody verified.
     *
     * @param resolved the value {@link #resolveLine} returned
     * @return {@code true} for a resolved line and for the refusals that still found the anchored text
     */
    public static boolean isFindable(int resolved)
    {
        return isResolved(resolved) || resolved == AMBIGUOUS || resolved == WEAK_EVIDENCE;
    }

    /**
     * Finds the line an issue's anchor points at, starting from the line number recorded for it.
     *
     * <p>The rules, in the order they are applied:
     * <ul>
     * <li>an empty {@code anchor} returns {@link #NO_ANCHOR}, and an unreadable one {@link #NOT_FOUND}:
     * an operation that rewrites source may not run on an unverified line, whichever way the verification is
     * missing;</li>
     * <li>candidates are the lines within {@link #SEARCH_RADIUS} of the recorded number whose own text still
     * hashes to the anchor's target hash. None: {@link #NOT_FOUND};</li>
     * <li>each candidate is scored against the context the anchor recorded (see {@link #scoreOf}). The best
     * one is taken only if it carries enough evidence in absolute terms, has evidence on both sides when the
     * anchor recorded context on both sides, and is at least {@link #EVIDENCE_MARGIN} points ahead of the
     * runner-up; otherwise {@link #WEAK_EVIDENCE} or {@link #AMBIGUOUS}.</li>
     * </ul>
     *
     * <p>The recorded line is a candidate like any other and nothing more - it is where an analysis that
     * predates every edit since believed the issue was, which says nothing about which of several identical
     * statements it meant. Reading it as evidence is precisely what wrapped the wrong copy.
     *
     * <p>Cheap enough for the marker synchronization, which resolves once per issue of a whole snapshot: the
     * window is normalized once per call and each line at most once, and only lines that already matched the
     * target hash are scored at all.
     *
     * @param document the current content of the file, not {@code null}
     * @param line1Based the 1-based line number recorded for the issue
     * @param anchor the recorded anchor, not {@code null}
     * @return the 1-based line to edit, or one of {@link #NOT_FOUND}, {@link #AMBIGUOUS},
     *     {@link #WEAK_EVIDENCE}, {@link #NO_ANCHOR} when there is no line this anchor allows editing (see
     *     {@link #isResolved})
     */
    public static int resolveLine(IDocument document, int line1Based, String anchor)
    {
        if (anchor.isEmpty())
        {
            return NO_ANCHOR;
        }
        Anchor parsed = parse(anchor);
        if (parsed == null)
        {
            return NOT_FOUND;
        }
        NormalizedLines lines = new NormalizedLines(document);
        int bestLine = NOT_FOUND;
        Score best = null;
        int runnerUp = -1;
        for (int candidate = line1Based - SEARCH_RADIUS; candidate <= line1Based + SEARCH_RADIUS; candidate++)
        {
            if (!lines.exists(candidate) || !parsed.targetHash.equals(hash(lines.at(candidate))))
            {
                continue;
            }
            Score score = scoreOf(lines, candidate, parsed);
            if (best == null || score.total() > best.total())
            {
                runnerUp = best != null ? best.total() : runnerUp;
                best = score;
                bestLine = candidate;
            }
            else if (score.total() > runnerUp)
            {
                runnerUp = score.total();
            }
        }
        if (best == null)
        {
            return NOT_FOUND;
        }
        return verdict(bestLine, best, runnerUp, parsed);
    }

    /**
     * Applies the acceptance rules to the best-scoring candidate.
     *
     * @param bestLine the 1-based line of the best-scoring candidate
     * @param best its score, not {@code null}
     * @param runnerUp the best total of any other candidate, or {@code -1} when there was no other
     * @param anchor the parsed anchor, not {@code null}
     * @return {@code bestLine}, or the refusal that applies
     */
    private static int verdict(int bestLine, Score best, int runnerUp, Anchor anchor)
    {
        if (best.total() < anchor.requiredEvidence)
        {
            return WEAK_EVIDENCE;
        }
        if (anchor.requiresBothSides() && (best.before() == 0 || best.after() == 0))
        {
            return WEAK_EVIDENCE;
        }
        if (runnerUp >= 0 && best.total() - runnerUp < EVIDENCE_MARGIN)
        {
            return AMBIGUOUS;
        }
        return bestLine;
    }

    /**
     * Scores one candidate line against the context an anchor recorded.
     *
     * <p>Per-line anchors are scored side by side, order-preservingly: the longest common subsequence of the
     * stored hashes and the candidate's own, on each side separately. That is what makes the score degrade by
     * one for one changed, inserted or deleted neighbour instead of collapsing to zero. Block-hash anchors
     * have no sides; each of their two blocks that still matches is worth a point.
     *
     * @param lines the document's normalized lines, not {@code null}
     * @param candidate the 1-based line to score
     * @param anchor the parsed anchor, not {@code null}
     * @return the score, never {@code null}
     */
    private static Score scoreOf(NormalizedLines lines, int candidate, Anchor anchor)
    {
        if (anchor.blocks.isEmpty())
        {
            return new Score(commonSubsequence(anchor.before, contextHashes(lines, candidate, -1)),
                commonSubsequence(anchor.after, contextHashes(lines, candidate, 1)));
        }
        int matched = 0;
        for (int index = 0; index < anchor.blocks.size(); index++)
        {
            if (anchor.blocks.get(index).equals(blockHash(lines, candidate, BLOCK_RADII[index])))
            {
                matched++;
            }
        }
        return new Score(matched, 0);
    }

    /**
     * The hashes of the eligible lines on one side of a line, nearest first.
     *
     * <p>Blank lines carry no evidence, and this plug-in's own {@code // BSLLS:} comments must carry none:
     * they are written by the very operation these anchors protect, so counting them would let one
     * suppression invalidate its neighbours' anchors. Both are skipped, within a budget of
     * {@link #CONTEXT_SCAN} physical lines, and the search stops at the edge of the file.
     *
     * @param lines the document's normalized lines, not {@code null}
     * @param line1Based the 1-based line to look around
     * @param direction {@code -1} for the lines above, {@code 1} for the lines below
     * @return up to {@link #CONTEXT_LINES} hashes, nearest first, never {@code null}
     */
    private static List<String> contextHashes(NormalizedLines lines, int line1Based, int direction)
    {
        List<String> hashes = new ArrayList<>(CONTEXT_LINES);
        for (int step = 1; step <= CONTEXT_SCAN && hashes.size() < CONTEXT_LINES; step++)
        {
            int line = line1Based + direction * step;
            if (!lines.exists(line))
            {
                break;
            }
            String text = lines.at(line);
            if (text.isEmpty() || BslSuppression.isBslSuppressionComment(text))
            {
                continue;
            }
            hashes.add(hash(text));
        }
        return hashes;
    }

    /**
     * The length of the longest common subsequence of two hash lists.
     *
     * <p>Order-preserving on purpose: an inserted or deleted neighbour must cost exactly the one line it is,
     * and must not shift every line behind it out of alignment the way a positional comparison would.
     *
     * @param stored the hashes the anchor recorded, not {@code null}
     * @param current the candidate's current hashes, not {@code null}
     * @return how many stored hashes are found, in order, in {@code current}
     */
    private static int commonSubsequence(List<String> stored, List<String> current)
    {
        int[][] best = new int[stored.size() + 1][current.size() + 1];
        for (int left = 1; left <= stored.size(); left++)
        {
            for (int right = 1; right <= current.size(); right++)
            {
                best[left][right] = stored.get(left - 1).equals(current.get(right - 1))
                    ? best[left - 1][right - 1] + 1
                    : Math.max(best[left - 1][right], best[left][right - 1]);
            }
        }
        return best[stored.size()][current.size()];
    }

    /**
     * Hashes the block of lines centred on one line, the way the block-hash anchor format recorded it.
     *
     * @param lines the document's normalized lines, not {@code null}
     * @param line1Based the 1-based number of the centre line
     * @param radius how many lines above and below the centre belong to the block
     * @return the hash, never {@code null}
     */
    private static String blockHash(NormalizedLines lines, int line1Based, int radius)
    {
        StringBuilder block = new StringBuilder();
        for (int offset = -radius; offset <= radius; offset++)
        {
            block.append(lines.at(line1Based + offset)).append(BLOCK_LINE_SEPARATOR);
        }
        return hash(block);
    }

    /**
     * Reads a serialized anchor.
     *
     * @param anchor the recorded anchor, not {@code null}
     * @return the parsed anchor, or {@code null} when it is empty or in a format this version cannot read,
     *     which the caller must treat as a refusal rather than as a missing check
     */
    private static Anchor parse(String anchor)
    {
        if (isHash(anchor))
        {
            // The pre-context format: the line's own hash, and nothing else to check it against.
            return new Anchor(anchor, List.of(), List.of(), List.of(), 0);
        }
        if (anchor.startsWith(VERSION_PREFIX))
        {
            return parseContextAnchor(anchor);
        }
        if (anchor.startsWith(BLOCK_VERSION_PREFIX))
        {
            return parseBlockAnchor(anchor);
        }
        return null;
    }

    /**
     * Reads a {@link #VERSION_PREFIX} anchor.
     *
     * @param anchor the recorded anchor, not {@code null}
     * @return the parsed anchor, or {@code null} when it is malformed
     */
    private static Anchor parseContextAnchor(String anchor)
    {
        String[] parts = anchor.substring(VERSION_PREFIX.length()).split(COMPONENT_SEPARATOR, -1);
        if (parts.length != COMPONENTS || !isHash(parts[0]))
        {
            return null;
        }
        List<String> before = parseHashes(parts[1]);
        List<String> after = parseHashes(parts[2]);
        if (before == null || after == null)
        {
            return null;
        }
        // A line at the very top or bottom of a file has less context to give than three lines; asking it for
        // evidence it could never have recorded would refuse it for good.
        int required = Math.min(EVIDENCE_MARGIN, before.size() + after.size());
        return new Anchor(parts[0], before, after, List.of(), required);
    }

    /**
     * Reads a {@link #BLOCK_VERSION_PREFIX} anchor.
     *
     * <p>Its two block hashes become one point of evidence each. One of them is enough to be believed - a
     * block hash attests to six neighbouring lines at once, so reproducing even the narrow one is a stronger
     * statement than a single matching line - but the margin over the runner-up applies unchanged.
     *
     * @param anchor the recorded anchor, not {@code null}
     * @return the parsed anchor, or {@code null} when it is malformed
     */
    private static Anchor parseBlockAnchor(String anchor)
    {
        String[] parts = anchor.substring(BLOCK_VERSION_PREFIX.length()).split(COMPONENT_SEPARATOR, -1);
        if (parts.length != BLOCK_COMPONENTS)
        {
            return null;
        }
        for (String part : parts)
        {
            if (!isHash(part))
            {
                return null;
            }
        }
        return new Anchor(parts[0], List.of(), List.of(), List.of(parts[1], parts[2]), 1);
    }

    /**
     * Reads one comma-separated list of context hashes.
     *
     * @param text the serialized component, not {@code null}; empty means "no context on this side"
     * @return the hashes, or {@code null} when the component is malformed
     */
    private static List<String> parseHashes(String text)
    {
        if (text.isEmpty())
        {
            return List.of();
        }
        String[] parts = text.split(CONTEXT_SEPARATOR, -1);
        if (parts.length > CONTEXT_LINES)
        {
            return null;
        }
        for (String part : parts)
        {
            if (!isHash(part))
            {
                return null;
            }
        }
        return List.of(parts);
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
     * A parsed anchor: what has to be found again, and how much of it a candidate has to reproduce.
     */
    private static final class Anchor
    {
        /** The hash of the flagged line itself; every candidate has to carry it. */
        private final String targetHash;

        /** The hashes of the eligible lines above it, nearest first; empty for the older formats. */
        private final List<String> before;

        /** The hashes of the eligible lines below it, nearest first; empty for the older formats. */
        private final List<String> after;

        /** The block hashes of a block-hash anchor, in serialization order; empty for the other formats. */
        private final List<String> blocks;

        /** How many points the best candidate needs before it may be edited at all. */
        private final int requiredEvidence;

        /**
         * @param targetHash the hash of the flagged line, not {@code null}
         * @param before the hashes above it, not {@code null}
         * @param after the hashes below it, not {@code null}
         * @param blocks the block hashes, not {@code null}
         * @param requiredEvidence how many points the best candidate needs
         */
        Anchor(String targetHash, List<String> before, List<String> after, List<String> blocks,
            int requiredEvidence)
        {
            this.targetHash = targetHash;
            this.before = before;
            this.after = after;
            this.blocks = blocks;
            this.requiredEvidence = requiredEvidence;
        }

        /**
         * Tells whether this anchor recorded context on both sides of the flagged line, in which case a
         * candidate has to answer on both: a block of code that only agrees above is exactly what a moved or
         * duplicated fragment looks like.
         *
         * @return {@code true} when both sides carry at least one hash
         */
        boolean requiresBothSides()
        {
            return !before.isEmpty() && !after.isEmpty();
        }
    }

    /**
     * What one candidate reproduced of an anchor's context, kept per side so the both-sides rule can be
     * applied.
     *
     * @param before matching evidence above the candidate (or, for a block-hash anchor, all of its evidence)
     * @param after matching evidence below the candidate
     */
    private record Score(int before, int after)
    {
        /**
         * @return the total evidence
         */
        int total()
        {
            return before + after;
        }
    }

    /**
     * A document's lines, normalized once and remembered for the duration of one anchor computation or one
     * lookup.
     *
     * <p>A lookup reads the same line as a neighbour of several candidates, and the marker synchronization
     * runs one lookup per issue of a whole snapshot, so normalizing on every read would be felt.
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
