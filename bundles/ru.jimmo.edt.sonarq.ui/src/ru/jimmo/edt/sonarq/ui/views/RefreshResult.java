/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.ui.views;

import ru.jimmo.edt.sonarq.core.model.IssueSnapshot;
import ru.jimmo.edt.sonarq.core.provider.BranchState;

/**
 * The outcome of a background issue refresh.
 *
 * @param snapshot the loaded issues, already anchored (see
 *     {@code ru.jimmo.edt.sonarq.ui.views.IssueAnchoring}), {@code null} on error
 * @param branchState the resolved branch state, {@code null} on error
 * @param errorMessage a user-facing error message, {@code null} on success
 * @param markerStateVersion the project issue state version the refresh reserved <em>before</em> it fetched
 *     anything (see {@code ru.jimmo.edt.sonarq.ui.markers.MarkerStateVersion}). Every marker write derived
 *     from this result has to carry it rather than publish a newer one, or a refresh a quick-suppress
 *     overtook would put pre-edit line numbers back on an already-edited file
 */
public record RefreshResult(IssueSnapshot snapshot, BranchState branchState, String errorMessage,
    long markerStateVersion)
{
    /** The version of a result no marker write may be derived from. */
    public static final long NO_STATE_VERSION = 0;

    /**
     * Creates a failed result carrying a user-facing message.
     *
     * @param errorMessage the error message to show, not {@code null}
     * @return the error result, never {@code null}
     */
    public static RefreshResult error(String errorMessage)
    {
        return new RefreshResult(null, null, errorMessage, NO_STATE_VERSION);
    }

    /**
     * Tells whether this result represents a failure.
     *
     * @return {@code true} when an error message is present
     */
    public boolean isError()
    {
        return errorMessage != null;
    }
}
