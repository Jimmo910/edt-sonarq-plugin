/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.core.anchors;

import ru.jimmo.edt.sonarq.core.suppress.LineAnchor;
import ru.jimmo.edt.sonarq.core.suppress.SuppressionLineShift;

/**
 * What the plug-in remembers about one issue between two refreshes - and, since this is written to disk,
 * across a restart of EDT.
 *
 * <p>The {@link #anchor()} is the whole point: a fingerprint of the code the issue was reported on, which
 * survives the line number going stale. {@link #lastKnownLine()} is a <em>hint</em> and nothing more - it is
 * where the anchor was last seen, so the search for it can start somewhere sensible. Nothing may edit a line
 * on the strength of it; only {@link LineAnchor#resolveLine} decides which line an anchor names.
 *
 * @param issueKey the issue key the analysis reported, unique within its {@link AnchorScope}, not
 *     {@code null}
 * @param ruleKey the rule key, kept for diagnostics and so a record can be recognized when an issue key is
 *     recycled, not {@code null}
 * @param anchor the serialized {@link LineAnchor} of the flagged line, not {@code null} and never
 *     {@link LineAnchor#NONE} in a stored record - an issue that could not be fingerprinted has nothing worth
 *     remembering
 * @param lastKnownLine the 1-based line the anchor last resolved to, a search hint only; {@code 0} when it
 *     was never resolved
 * @param lastSeen when this record was last confirmed by an analysis, in epoch milliseconds; drives the
 *     eviction order and the unseen-record TTL
 */
public record AnchorRecord(String issueKey, String ruleKey, String anchor, int lastKnownLine, long lastSeen)
{
    /** The {@link #lastKnownLine()} of a record whose anchor has never been located in the file. */
    public static final int NO_LINE = 0;

    /**
     * Returns a copy confirmed by an analysis that has just run.
     *
     * @param line the 1-based line the anchor resolved to, or {@link #NO_LINE} to keep the hint unchanged -
     *     which is what a refresh that could <em>not</em> resolve the anchor must do, because the reported
     *     line is not evidence about where the code went
     * @param nowMillis the current time in epoch milliseconds
     * @return the updated record, never {@code null}
     */
    public AnchorRecord seenAt(int line, long nowMillis)
    {
        return new AnchorRecord(issueKey, ruleKey, anchor, line > 0 ? line : lastKnownLine, nowMillis);
    }

    /**
     * Returns a copy whose hint accounts for the two comment lines a quick-suppress just inserted around
     * {@code codeLine}, with exactly the arithmetic every other line-numbered model in this plug-in uses.
     *
     * <p>The anchor itself is untouched: the comment pair moved the flagged line, it did not change its text.
     *
     * @param codeLine the 1-based line the {@code -off}/{@code -on} pair was wrapped around, in the numbering
     *     the file had before the insertion
     * @return the shifted record, or {@code this} when the hint is unaffected, never {@code null}
     */
    public AnchorRecord shiftedFor(int codeLine)
    {
        if (lastKnownLine <= 0)
        {
            return this;
        }
        int shifted = SuppressionLineShift.shiftedLine(lastKnownLine, codeLine);
        return shifted == lastKnownLine ? this
            : new AnchorRecord(issueKey, ruleKey, anchor, shifted, lastSeen);
    }
}
