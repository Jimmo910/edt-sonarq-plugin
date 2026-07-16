/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.core.model;

/**
 * Issue search scope.
 *
 * @param projectKey the SonarQube project key, not {@code null}
 * @param branch the branch name, or {@code null} to omit the branch parameter
 */
public record IssueQuery(String projectKey, String branch)
{
}
