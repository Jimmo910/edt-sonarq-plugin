/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.ui.views;

import ru.jimmo.edt.sonarq.core.model.SonarIssue;

/**
 * A single issue tree leaf: the underlying {@link SonarIssue} plus its resolved workspace-relative path.
 *
 * @param issue the underlying issue, not {@code null}
 * @param relativePath the project-relative path of the mapped file, {@code null} when the issue's
 *     component could not be mapped to a file in the bound EDT project
 */
public record IssueEntry(SonarIssue issue, String relativePath)
{
}
