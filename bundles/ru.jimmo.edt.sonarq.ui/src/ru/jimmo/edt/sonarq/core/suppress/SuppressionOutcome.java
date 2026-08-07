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
 * {@link #INSERTED} may renumber a caller's in-memory line numbers (see {@link SuppressionLineShift}), and
 * every other value is a refusal the user has to be told about, because from the outside a refused
 * suppression looks exactly like a menu entry that did nothing.
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
