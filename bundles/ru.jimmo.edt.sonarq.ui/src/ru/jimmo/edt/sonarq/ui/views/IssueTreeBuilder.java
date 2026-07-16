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
        for (SonarIssue issue : issues)
        {
            String path = ComponentPathMapper.toProjectRelativePath(issue.componentKey(), projectKey, pathPrefix)
                .orElse(null);
            IssueEntry entry = new IssueEntry(issue, path);
            if (grouping == IssueGrouping.BY_RULE)
            {
                mapped.computeIfAbsent(issue.ruleKey(), key -> new ArrayList<>()).add(entry);
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

    private static List<IssueEntry> sorted(List<IssueEntry> entries)
    {
        entries.sort(Comparator
            .comparing((IssueEntry entry) -> entry.relativePath() != null ? entry.relativePath() : "") //$NON-NLS-1$
            .thenComparingInt(entry -> entry.issue().line()));
        return entries;
    }
}
