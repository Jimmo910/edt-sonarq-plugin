/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.ui.views;

/** The grouping mode used to build the issues tree. */
public enum IssueGrouping
{
    /** Group issues by their mapped file path. */
    BY_FILE,

    /** Group issues by their rule key. */
    BY_RULE,

    /**
     * Group issues by severity, ordered by severity rank (BLOCKER first, INFO last; see
     * {@link ru.jimmo.edt.sonarq.core.model.SonarSeverity}), not alphabetically.
     */
    BY_SEVERITY
}
