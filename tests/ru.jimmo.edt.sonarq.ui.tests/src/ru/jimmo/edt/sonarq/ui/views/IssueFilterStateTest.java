/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.ui.views;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import ru.jimmo.edt.sonarq.core.model.SonarIssue;
import ru.jimmo.edt.sonarq.core.model.SonarIssueType;
import ru.jimmo.edt.sonarq.core.model.SonarSeverity;

/** Tests for {@link IssueFilterState}. */
public class IssueFilterStateTest
{
    private static SonarIssue issue(SonarSeverity severity, SonarIssueType type, String rule, String message)
    {
        return new SonarIssue("k", rule, severity, type, "p:src/M.bsl", message, 1); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void defaultStateMatchesEverything()
    {
        assertTrue(new IssueFilterState()
            .matches(issue(SonarSeverity.INFO, SonarIssueType.UNKNOWN, "bsl:R", "m"))); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void disabledSeverityFiltersOut()
    {
        IssueFilterState state = new IssueFilterState();
        state.toggleSeverity(SonarSeverity.MINOR);
        assertFalse(state.matches(
            issue(SonarSeverity.MINOR, SonarIssueType.BUG, "bsl:R", "m"))); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(state.matches(
            issue(SonarSeverity.MAJOR, SonarIssueType.BUG, "bsl:R", "m"))); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void textMatchesRuleOrMessageIgnoringCase()
    {
        IssueFilterState state = new IssueFilterState();
        state.setText("methodSIZE"); //$NON-NLS-1$
        assertTrue(state.matches(
            issue(SonarSeverity.MAJOR, SonarIssueType.BUG, "bsl:MethodSize", "x"))); //$NON-NLS-1$ //$NON-NLS-2$
        state.setText("too long"); //$NON-NLS-1$
        assertTrue(state.matches(
            issue(SonarSeverity.MAJOR, SonarIssueType.BUG, "bsl:R", //$NON-NLS-1$ //$NON-NLS-2$
                "Method Too Long here"))); //$NON-NLS-1$
        state.setText("absent"); //$NON-NLS-1$
        assertFalse(state.matches(
            issue(SonarSeverity.MAJOR, SonarIssueType.BUG, "bsl:R", "m"))); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
