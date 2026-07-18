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
        return issue(component, rule, line, SonarSeverity.MAJOR);
    }

    private static SonarIssue issue(String component, String rule, int line, SonarSeverity severity)
    {
        return new SonarIssue("k" + component + line, rule, severity,
            SonarIssueType.CODE_SMELL, component, "msg", line);
    }

    @Test
    public void groupsByFileSortedByPathAndLine()
    {
        List<IssueGroup> groups = IssueTreeBuilder.build(List.of(
            issue("p:src/B.bsl", "bsl:R1", 5),
            issue("p:src/A.bsl", "bsl:R1", 9),
            issue("p:src/A.bsl", "bsl:R2", 2)),
            "p", null, IssueGrouping.BY_FILE);
        assertEquals(2, groups.size());
        assertEquals("src/A.bsl", groups.get(0).label());
        assertEquals(2, groups.get(0).entries().get(0).issue().line());
        assertEquals(9, groups.get(0).entries().get(1).issue().line());
    }

    @Test
    public void unmappedIssuesGoToTrailingGroup()
    {
        List<IssueGroup> groups = IssueTreeBuilder.build(List.of(
            issue("p:src/A.bsl", "bsl:R1", 1),
            issue("other:src/X.bsl", "bsl:R1", 1)),
            "p", null, IssueGrouping.BY_FILE);
        assertEquals(2, groups.size());
        assertEquals(Messages.IssuesView_UnmappedGroup, groups.get(1).label());
        assertNull(groups.get(1).entries().get(0).relativePath());
    }

    @Test
    public void groupsByRule()
    {
        List<IssueGroup> groups = IssueTreeBuilder.build(List.of(
            issue("p:src/A.bsl", "bsl:R2", 1),
            issue("p:src/B.bsl", "bsl:R1", 1)),
            "p", null, IssueGrouping.BY_RULE);
        assertEquals("bsl:R1", groups.get(0).label());
        assertEquals("bsl:R2", groups.get(1).label());
    }

    /**
     * Regression test for issue #5: grouping by severity must order groups by severity rank (BLOCKER
     * first, INFO last, per {@link SonarSeverity}'s declaration order), not alphabetically - alphabetical
     * order would put BLOCKER, CRITICAL, INFO, MAJOR, MINOR, burying the most severe issues under INFO.
     */
    @Test
    public void groupsBySeverityOrderedByRankNotAlphabetically()
    {
        List<IssueGroup> groups = IssueTreeBuilder.build(List.of(
            issue("p:src/A.bsl", "bsl:R1", 1, SonarSeverity.INFO),
            issue("p:src/B.bsl", "bsl:R1", 2, SonarSeverity.BLOCKER),
            issue("p:src/C.bsl", "bsl:R1", 3, SonarSeverity.MINOR),
            issue("p:src/D.bsl", "bsl:R1", 4, SonarSeverity.CRITICAL),
            issue("p:src/E.bsl", "bsl:R1", 5, SonarSeverity.MAJOR)),
            "p", null, IssueGrouping.BY_SEVERITY);

        assertEquals(5, groups.size());
        assertEquals("BLOCKER", groups.get(0).label());
        assertEquals("CRITICAL", groups.get(1).label());
        assertEquals("MAJOR", groups.get(2).label());
        assertEquals("MINOR", groups.get(3).label());
        assertEquals("INFO", groups.get(4).label());
    }

    @Test
    public void groupsBySeverityCountsEntriesPerGroup()
    {
        List<IssueGroup> groups = IssueTreeBuilder.build(List.of(
            issue("p:src/A.bsl", "bsl:R1", 1, SonarSeverity.MAJOR),
            issue("p:src/B.bsl", "bsl:R1", 2, SonarSeverity.MAJOR),
            issue("p:src/C.bsl", "bsl:R1", 3, SonarSeverity.BLOCKER)),
            "p", null, IssueGrouping.BY_SEVERITY);

        assertEquals(2, groups.size());
        assertEquals("BLOCKER", groups.get(0).label());
        assertEquals(1, groups.get(0).entries().size());
        assertEquals("MAJOR", groups.get(1).label());
        assertEquals(2, groups.get(1).entries().size());
    }

    @Test
    public void toEntriesMapsPathForMatchingProject()
    {
        List<IssueEntry> entries =
            IssueTreeBuilder.toEntries(List.of(issue("p:src/A.bsl", "bsl:R1", 3)), "p", null);
        assertEquals(1, entries.size());
        assertEquals("src/A.bsl", entries.get(0).relativePath());
    }

    @Test
    public void toEntriesNullPathForForeignProject()
    {
        List<IssueEntry> entries =
            IssueTreeBuilder.toEntries(List.of(issue("other:src/X.bsl", "bsl:R1", 1)), "p", null);
        assertEquals(1, entries.size());
        assertNull(entries.get(0).relativePath());
    }

    @Test
    public void countUnmappedIsZeroWhenEveryEntryMapped()
    {
        List<IssueEntry> entries =
            IssueTreeBuilder.toEntries(List.of(issue("p:src/A.bsl", "bsl:R1", 1)), "p", null);
        assertEquals(0, IssueTreeBuilder.countUnmapped(entries));
    }

    /**
     * Regression test for issue #4 point 5: the status line should surface how many issues are shown in the
     * tree but not as Problems-view markers (i.e. did not map to a workspace file), using the same mapping
     * the tree and marker sync use.
     */
    @Test
    public void countUnmappedCountsOnlyEntriesWithoutARelativePath()
    {
        List<IssueEntry> entries = IssueTreeBuilder.toEntries(List.of(
            issue("p:src/A.bsl", "bsl:R1", 1),
            issue("other:src/X.bsl", "bsl:R1", 1),
            issue("other:src/Y.bsl", "bsl:R1", 2)),
            "p", null);
        assertEquals(2, IssueTreeBuilder.countUnmapped(entries));
    }

    @Test
    public void toEntriesPreservesInputOrderAndCount()
    {
        List<SonarIssue> issues = List.of(
            issue("p:src/B.bsl", "bsl:R1", 5),
            issue("p:src/A.bsl", "bsl:R1", 9),
            issue("other:src/X.bsl", "bsl:R2", 2));

        List<IssueEntry> entries = IssueTreeBuilder.toEntries(issues, "p", null);

        assertEquals(issues.size(), entries.size());
        for (int i = 0; i < issues.size(); i++)
        {
            assertEquals(issues.get(i), entries.get(i).issue());
        }
    }
}
