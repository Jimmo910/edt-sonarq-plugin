/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.core.model;

import java.time.Instant;
import java.util.List;

/**
 * A point-in-time snapshot of issues loaded for one {@link IssueQuery}.
 *
 * @param query the query this snapshot was loaded for, not {@code null}
 * @param issues the loaded issues, not {@code null}
 * @param serverTotal the total number of issues reported by the server
 * @param loadedAt the instant the snapshot was loaded, not {@code null}
 */
public record IssueSnapshot(IssueQuery query, List<SonarIssue> issues, int serverTotal, Instant loadedAt)
{
    /**
     * Tells whether the server reported more issues than were fetched (10 000 cap).
     *
     * @return {@code true} when the snapshot is incomplete
     */
    public boolean truncated()
    {
        return serverTotal > issues.size();
    }
}
