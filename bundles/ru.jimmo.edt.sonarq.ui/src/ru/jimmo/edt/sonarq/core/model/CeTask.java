/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.core.model;

/**
 * Status of a SonarQube Compute Engine (background analysis) task.
 *
 * @param status the task status, e.g. {@code SUCCESS}, {@code FAILED}, {@code CANCELED} or
 *     {@code IN_PROGRESS}, not {@code null}
 * @param errorMessage the error message if the task failed, or an empty string if none
 */
public record CeTask(String status, String errorMessage)
{
    /**
     * Returns whether the task has reached a terminal status.
     *
     * @return {@code true} if the status is {@code SUCCESS}, {@code FAILED} or {@code CANCELED}
     */
    public boolean terminal()
    {
        return "SUCCESS".equals(status) //$NON-NLS-1$
            || "FAILED".equals(status) //$NON-NLS-1$
            || "CANCELED".equals(status); //$NON-NLS-1$
    }

    /**
     * Returns whether the task completed successfully.
     *
     * @return {@code true} if the status is {@code SUCCESS}
     */
    public boolean success()
    {
        return "SUCCESS".equals(status); //$NON-NLS-1$
    }
}
