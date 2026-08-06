/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.ui.views;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

import ru.jimmo.edt.sonarq.core.model.SonarIssue;
import ru.jimmo.edt.sonarq.core.model.SonarIssueType;
import ru.jimmo.edt.sonarq.core.model.SonarSeverity;

/** Tests for {@link IssueFilterState}. */
public class IssueFilterStateTest
{
    private static SonarIssue issue(SonarSeverity severity, SonarIssueType type, String rule, String message)
    {
        return new SonarIssue("k", rule, severity, type, "p:src/M.bsl", message, 1);
    }

    @Test
    public void defaultStateMatchesEverything()
    {
        assertTrue(new IssueFilterState()
            .matches(issue(SonarSeverity.INFO, SonarIssueType.UNKNOWN, "bsl:R", "m")));
    }

    @Test
    public void disabledSeverityFiltersOut()
    {
        IssueFilterState state = new IssueFilterState();
        state.toggleSeverity(SonarSeverity.MINOR);
        assertFalse(state.matches(issue(SonarSeverity.MINOR, SonarIssueType.BUG, "bsl:R", "m")));
        assertTrue(state.matches(issue(SonarSeverity.MAJOR, SonarIssueType.BUG, "bsl:R", "m")));
    }

    @Test
    public void groupCountReflectsOnlyMatchingEntries()
    {
        // The group header shows this number, so it has to agree with what IssueViewerFilter leaves visible:
        // three entries in the group, one of them filtered out by severity.
        IssueGroup group = new IssueGroup("src/M.bsl", List.of(
            new IssueEntry(issue(SonarSeverity.MAJOR, SonarIssueType.BUG, "bsl:R", "a"), "src/M.bsl"),
            new IssueEntry(issue(SonarSeverity.MINOR, SonarIssueType.BUG, "bsl:R", "b"), "src/M.bsl"),
            new IssueEntry(issue(SonarSeverity.MAJOR, SonarIssueType.BUG, "bsl:R", "c"), "src/M.bsl")));
        IssueFilterState state = new IssueFilterState();
        assertEquals(3, state.countMatching(group));
        state.toggleSeverity(SonarSeverity.MINOR);
        assertEquals(2, state.countMatching(group));
        state.setText("a");
        assertEquals(1, state.countMatching(group));
    }

    @Test
    public void superGroupCountSumsItsSubGroups()
    {
        IssueGroup rule1 = new IssueGroup("bsl:R1", List.of(
            new IssueEntry(issue(SonarSeverity.MAJOR, SonarIssueType.BUG, "bsl:R1", "a"), "src/M.bsl"),
            new IssueEntry(issue(SonarSeverity.MAJOR, SonarIssueType.CODE_SMELL, "bsl:R1", "b"), "src/M.bsl")));
        IssueGroup rule2 = new IssueGroup("bsl:R2", List.of(
            new IssueEntry(issue(SonarSeverity.MAJOR, SonarIssueType.BUG, "bsl:R2", "c"), "src/M.bsl")));
        IssueSuperGroup superGroup = new IssueSuperGroup("MAJOR", List.of(rule1, rule2));
        IssueFilterState state = new IssueFilterState();
        assertEquals(3, state.countMatching(superGroup));
        state.toggleType(SonarIssueType.BUG);
        assertEquals(1, state.countMatching(superGroup));
    }

    @Test
    public void hiddenNodesCountZero()
    {
        IssueGroup group = new IssueGroup("src/M.bsl", List.of(
            new IssueEntry(issue(SonarSeverity.MINOR, SonarIssueType.BUG, "bsl:R", "a"), "src/M.bsl")));
        IssueFilterState state = new IssueFilterState();
        state.toggleSeverity(SonarSeverity.MINOR);
        assertEquals(0, state.countMatching(group));
        assertEquals(0, state.countMatching(new IssueSuperGroup("MINOR", List.of(group))));
    }

    @Test
    public void textMatchesRuleOrMessageIgnoringCase()
    {
        IssueFilterState state = new IssueFilterState();
        state.setText("methodSIZE");
        assertTrue(state.matches(issue(SonarSeverity.MAJOR, SonarIssueType.BUG, "bsl:MethodSize", "x")));
        state.setText("too long");
        assertTrue(state.matches(issue(SonarSeverity.MAJOR, SonarIssueType.BUG, "bsl:R", "Method Too Long here")));
        state.setText("absent");
        assertFalse(state.matches(issue(SonarSeverity.MAJOR, SonarIssueType.BUG, "bsl:R", "m")));
    }
}
