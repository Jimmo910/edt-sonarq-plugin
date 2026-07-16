/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.ui.views;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.List;

import org.junit.Test;

import ru.jimmo.edt.sonarq.core.model.SonarIssue;
import ru.jimmo.edt.sonarq.core.model.SonarIssueType;
import ru.jimmo.edt.sonarq.core.model.SonarSeverity;
import ru.jimmo.edt.sonarq.ui.Messages;

/** Tests for {@link IssueTreeBuilder}. */
public class IssueTreeBuilderTest
{
    private static SonarIssue issue(String component, String rule, int line)
    {
        return new SonarIssue("k" + component + line, rule, SonarSeverity.MAJOR, //$NON-NLS-1$
            SonarIssueType.CODE_SMELL, component, "msg", line); //$NON-NLS-1$
    }

    @Test
    public void groupsByFileSortedByPathAndLine()
    {
        List<IssueGroup> groups = IssueTreeBuilder.build(List.of(
            issue("p:src/B.bsl", "bsl:R1", 5), //$NON-NLS-1$ //$NON-NLS-2$
            issue("p:src/A.bsl", "bsl:R1", 9), //$NON-NLS-1$ //$NON-NLS-2$
            issue("p:src/A.bsl", "bsl:R2", 2)), //$NON-NLS-1$ //$NON-NLS-2$
            "p", null, IssueGrouping.BY_FILE); //$NON-NLS-1$
        assertEquals(2, groups.size());
        assertEquals("src/A.bsl", groups.get(0).label()); //$NON-NLS-1$
        assertEquals(2, groups.get(0).entries().get(0).issue().line());
        assertEquals(9, groups.get(0).entries().get(1).issue().line());
    }

    @Test
    public void unmappedIssuesGoToTrailingGroup()
    {
        List<IssueGroup> groups = IssueTreeBuilder.build(List.of(
            issue("p:src/A.bsl", "bsl:R1", 1), //$NON-NLS-1$ //$NON-NLS-2$
            issue("other:src/X.bsl", "bsl:R1", 1)), //$NON-NLS-1$ //$NON-NLS-2$
            "p", null, IssueGrouping.BY_FILE); //$NON-NLS-1$
        assertEquals(2, groups.size());
        assertEquals(Messages.IssuesView_UnmappedGroup, groups.get(1).label());
        assertNull(groups.get(1).entries().get(0).relativePath());
    }

    @Test
    public void groupsByRule()
    {
        List<IssueGroup> groups = IssueTreeBuilder.build(List.of(
            issue("p:src/A.bsl", "bsl:R2", 1), //$NON-NLS-1$ //$NON-NLS-2$
            issue("p:src/B.bsl", "bsl:R1", 1)), //$NON-NLS-1$ //$NON-NLS-2$
            "p", null, IssueGrouping.BY_RULE); //$NON-NLS-1$
        assertEquals("bsl:R1", groups.get(0).label()); //$NON-NLS-1$
        assertEquals("bsl:R2", groups.get(1).label()); //$NON-NLS-1$
    }
}
