/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.ui.sync;

import org.eclipse.core.runtime.jobs.ISchedulingRule;

/**
 * A scheduling rule that serializes the SonarQube refresh and analysis jobs of a single project.
 *
 * <p>Two rules conflict only when they name the same project, so the Eclipse job manager runs at most one
 * refresh or analysis per project at a time — a replacement job scheduled after cancelling the previous one
 * waits for it to actually finish before starting, instead of racing it over the same managed analyzer
 * install, SARIF output and {@code scannerwork} directories. The rule deliberately does not conflict with
 * workspace resource rules, so it never blocks edits, builds or saves on the project while an analysis runs.
 */
public final class ProjectAnalysisRule implements ISchedulingRule
{
    private final String projectName;

    /**
     * Creates a rule for the given project.
     *
     * @param projectName the project name, not {@code null}
     */
    public ProjectAnalysisRule(String projectName)
    {
        this.projectName = projectName;
    }

    @Override
    public boolean contains(ISchedulingRule rule)
    {
        return isConflicting(rule);
    }

    @Override
    public boolean isConflicting(ISchedulingRule rule)
    {
        return rule instanceof ProjectAnalysisRule other && other.projectName.equals(projectName);
    }
}
