/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.ui.views;

import java.util.List;

/**
 * A first-level node of the by-severity issues tree: a severity label with its child {@link IssueGroup rule
 * groups}, each of which in turn holds the {@link IssueEntry issues} reported by one rule at that severity.
 *
 * <p>Only the {@link IssueGrouping#BY_SEVERITY} tree uses this three-level shape (severity, then rule, then
 * issue); the by-file and by-rule trees stay two-level ({@link IssueGroup} directly over {@link IssueEntry}).
 *
 * @param label the severity label, not {@code null}
 * @param groups the per-rule groups belonging to this severity, not {@code null}
 */
public record IssueSuperGroup(String label, List<IssueGroup> groups)
{
    // The header count of such a node is not the raw number of issues it holds but the number the active
    // filters leave visible, so it lives with the filter state (see IssueFilterState#countMatching) rather
    // than here.
}
