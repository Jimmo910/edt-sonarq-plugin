/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.ui.views;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import ru.jimmo.edt.sonarq.core.mapping.ComponentPathMapper;
import ru.jimmo.edt.sonarq.core.model.SonarIssue;
import ru.jimmo.edt.sonarq.ui.Messages;

/** Builds the issue tree grouped by file or by rule. */
public final class IssueTreeBuilder
{
    private IssueTreeBuilder()
    {
    }

    /**
     * Builds sorted issue groups.
     *
     * @param issues the issues, not {@code null}
     * @param projectKey the SonarQube project key, not {@code null}
     * @param pathPrefix the repository path prefix, may be {@code null}
     * @param grouping the grouping mode, not {@code null}
     * @return the groups, never {@code null}
     */
    public static List<IssueGroup> build(List<SonarIssue> issues, String projectKey, String pathPrefix,
        IssueGrouping grouping)
    {
        Map<String, List<IssueEntry>> mapped = new TreeMap<>();
        List<IssueEntry> unmapped = new ArrayList<>();
        for (IssueEntry entry : toEntries(issues, projectKey, pathPrefix))
        {
            String path = entry.relativePath();
            if (grouping == IssueGrouping.BY_RULE)
            {
                mapped.computeIfAbsent(entry.issue().ruleKey(), key -> new ArrayList<>()).add(entry);
            }
            else if (path != null)
            {
                mapped.computeIfAbsent(path, key -> new ArrayList<>()).add(entry);
            }
            else
            {
                unmapped.add(entry);
            }
        }
        List<IssueGroup> result = new ArrayList<>();
        for (Map.Entry<String, List<IssueEntry>> group : mapped.entrySet())
        {
            result.add(new IssueGroup(group.getKey(), sorted(group.getValue())));
        }
        if (!unmapped.isEmpty())
        {
            result.add(new IssueGroup(Messages.IssuesView_UnmappedGroup, sorted(unmapped)));
        }
        return result;
    }

    /**
     * Maps every issue to an {@link IssueEntry}, resolving its workspace-relative path.
     *
     * <p>Performs no sorting or grouping: the returned list has the same size and order as
     * {@code issues}. An issue whose component does not map to a file under {@code projectKey}
     * (optionally scoped to {@code pathPrefix}) gets a {@code null} {@link IssueEntry#relativePath()}.
     *
     * @param issues the issues, not {@code null}
     * @param projectKey the SonarQube project key, not {@code null}
     * @param pathPrefix the repository path prefix, may be {@code null}
     * @return the mapped entries, never {@code null}, same size and order as {@code issues}
     */
    public static List<IssueEntry> toEntries(List<SonarIssue> issues, String projectKey, String pathPrefix)
    {
        List<IssueEntry> entries = new ArrayList<>();
        for (SonarIssue issue : issues)
        {
            String path = ComponentPathMapper.toProjectRelativePath(issue.componentKey(), projectKey, pathPrefix)
                .orElse(null);
            entries.add(new IssueEntry(issue, path));
        }
        return entries;
    }

    /**
     * Counts entries whose component did not map to a file in the bound EDT project.
     *
     * <p>Used to surface, in the status line, how many issues are shown in the tree but not as Problems-view
     * markers (see {@link ru.jimmo.edt.sonarq.ui.markers.IssueMarkerSynchronizer}, which only ever creates
     * markers for mapped entries).
     *
     * @param entries the mapped entries (e.g. from {@link #toEntries}), not {@code null}
     * @return the number of entries with a {@code null} {@link IssueEntry#relativePath()}
     */
    public static long countUnmapped(List<IssueEntry> entries)
    {
        return entries.stream().filter(entry -> entry.relativePath() == null).count();
    }

    private static List<IssueEntry> sorted(List<IssueEntry> entries)
    {
        entries.sort(Comparator
            .comparing((IssueEntry entry) -> entry.relativePath() != null ? entry.relativePath() : "") //$NON-NLS-1$
            .thenComparingInt(entry -> entry.issue().line()));
        return entries;
    }
}
