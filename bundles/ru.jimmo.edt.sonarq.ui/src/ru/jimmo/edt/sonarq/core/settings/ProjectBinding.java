/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.core.settings;

/**
 * The SonarQube binding of an EDT project.
 *
 * @param projectKey the SonarQube project key, not {@code null}, empty means not configured
 * @param branchOverride the branch name to use instead of the detected git branch, not {@code null}, may be empty
 * @param pathPrefix the repository sub-directory holding the EDT project, not {@code null}, may be empty
 */
public record ProjectBinding(String projectKey, String branchOverride, String pathPrefix)
{
    /**
     * Tells whether this binding points to a SonarQube project.
     *
     * @return {@code true} when {@link #projectKey()} is not empty
     */
    public boolean isConfigured()
    {
        return !projectKey.isEmpty();
    }
}
