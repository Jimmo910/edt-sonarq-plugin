/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.ui.views;

import java.util.List;

/**
 * A top-level node of the issues tree: a group label (a file path, a rule key, or the unmapped
 * placeholder) with its child {@link IssueEntry issues}.
 *
 * @param label the group label, not {@code null}
 * @param entries the issues belonging to this group, not {@code null}
 */
public record IssueGroup(String label, List<IssueEntry> entries)
{
}
