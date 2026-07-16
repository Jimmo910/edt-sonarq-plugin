/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.core.model;

import java.util.List;

/**
 * One page of the {@code /api/issues/search} response.
 *
 * @param issues the issues on this page, not {@code null}
 * @param total the total number of issues reported by the server
 * @param pageIndex the 1-based page index
 * @param pageSize the requested page size
 */
public record IssuesPage(List<SonarIssue> issues, int total, int pageIndex, int pageSize)
{
}
