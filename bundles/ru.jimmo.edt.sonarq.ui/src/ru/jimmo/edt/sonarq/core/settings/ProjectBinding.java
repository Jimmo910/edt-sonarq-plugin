/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.core.settings;

import java.util.List;

/**
 * The SonarQube binding and local-analysis scope of an EDT project.
 *
 * @param projectKey the SonarQube project key, not {@code null}, empty means not configured
 * @param branchOverride the branch name to use instead of the detected git branch, not {@code null}
 * @param pathPrefix the repository sub-directory holding the EDT project, not {@code null}
 * @param baseBranch the git base branch or commit to diff against in local mode, not {@code null}, empty
 *     means no base-branch scoping
 * @param subsystems the subsystem names to restrict local analysis to, not {@code null}, empty means all
 */
public record ProjectBinding(String projectKey, String branchOverride, String pathPrefix, String baseBranch,
    List<String> subsystems)
{
    /** Defensively copies {@code subsystems} so the record stays immutable. */
    public ProjectBinding
    {
        subsystems = List.copyOf(subsystems);
    }

    /**
     * Convenience constructor for a binding without local-analysis scope.
     *
     * @param projectKey the SonarQube project key, not {@code null}
     * @param branchOverride the fixed branch, not {@code null}
     * @param pathPrefix the repository sub-directory, not {@code null}
     */
    public ProjectBinding(String projectKey, String branchOverride, String pathPrefix)
    {
        this(projectKey, branchOverride, pathPrefix, "", List.of()); //$NON-NLS-1$
    }

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
