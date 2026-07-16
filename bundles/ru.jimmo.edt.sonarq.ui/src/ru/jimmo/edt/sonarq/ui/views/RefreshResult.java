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
 * @param snapshot the loaded issues, {@code null} on error
 * @param branchState the resolved branch state, {@code null} on error
 * @param errorMessage a user-facing error message, {@code null} on success
 */
public record RefreshResult(IssueSnapshot snapshot, BranchState branchState, String errorMessage)
{
    /**
     * Creates a failed result carrying a user-facing message.
     *
     * @param errorMessage the error message to show, not {@code null}
     * @return the error result, never {@code null}
     */
    public static RefreshResult error(String errorMessage)
    {
        return new RefreshResult(null, null, errorMessage);
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
