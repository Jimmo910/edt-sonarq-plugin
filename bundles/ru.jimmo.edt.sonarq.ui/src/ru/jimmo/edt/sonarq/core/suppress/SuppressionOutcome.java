/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.core.suppress;

/**
 * What one quick-suppress attempt did to the file - the comment pair was written, or nothing was, and why.
 *
 * <p>A suppression edits the user's own source, so every caller has to distinguish the two: only
 * {@link #INSERTED} may renumber a caller's in-memory line numbers (see {@link SuppressionLineShift}, and
 * {@link SuppressionResult} for the line to renumber around), and every other value is a refusal the user has
 * to be told about, because from the outside a refused suppression looks exactly like a menu entry that did
 * nothing.
 */
public enum SuppressionOutcome
{
    /** The {@code -off}/{@code -on} comment pair was written around the anchored line. */
    INSERTED,

    /** The line is already wrapped in this rule's suppression, or is itself a suppression comment. */
    ALREADY_SUPPRESSED,

    /**
     * The line recorded for the issue no longer carries the issue's anchor, and no line near it does either:
     * the file changed since the issues were loaded, so there is no line this call may safely edit.
     */
    ANCHOR_NOT_FOUND,

    /**
     * Several lines near the recorded one carry the issue's anchor and the code around them cannot put one of
     * them clearly ahead of the others (see {@link LineAnchor#AMBIGUOUS}). Editing the nearest of them would
     * be a guess, and a guess here rewrites the user's source, so nothing is written.
     */
    ANCHOR_AMBIGUOUS,

    /**
     * The flagged line was found, but too little of the code the anchor recorded around it is still there to
     * call it the same line (see {@link LineAnchor#WEAK_EVIDENCE}) - the neighbourhood was rewritten since
     * the analysis. One repeated statement is not enough to wrap on.
     */
    ANCHOR_UNCERTAIN,

    /**
     * The issue carries no anchor at all: its file could not be read when the issues were mapped, so the
     * flagged line was never fingerprinted (see {@link LineAnchor#NO_ANCHOR}). The recorded line number is
     * then the only thing pointing at the code, and an operation that rewrites the user's source does not run
     * on an unverified number - the user is told to refresh the issues instead.
     */
    ANCHOR_MISSING,

    /** The file holds unsaved changes, which this plug-in must neither commit nor edit around. */
    UNSAVED_CHANGES,

    /** The file could not be opened for editing at all (no text file buffer was available for it). */
    NO_BUFFER;

    /**
     * Tells whether the file was really changed.
     *
     * @return {@code true} only for {@link #INSERTED}
     */
    public boolean inserted()
    {
        return this == INSERTED;
    }
}
