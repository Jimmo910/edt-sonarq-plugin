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
import ru.jimmo.edt.sonarq.core.model.SonarSeverity;
import ru.jimmo.edt.sonarq.ui.Messages;

/** Builds the issue tree grouped by file, by rule, or by severity. */
public final class IssueTreeBuilder
{
    private IssueTreeBuilder()
    {
    }

    /**
     * Builds the sorted issue tree nodes for the requested grouping.
     *
     * <p>The element type of the returned list depends on {@code grouping}: {@link IssueGrouping#BY_FILE}
     * and {@link IssueGrouping#BY_RULE} produce a flat list of {@link IssueGroup} (two-level tree), while
     * {@link IssueGrouping#BY_SEVERITY} produces a list of {@link IssueSuperGroup} (three-level tree:
     * severity, then rule, then issue). Callers treat the result opaquely (see
     * {@link IssueTreeContentProvider}).
     *
     * @param issues the issues, not {@code null}
     * @param projectKey the SonarQube project key, not {@code null}
     * @param pathPrefix the repository path prefix, may be {@code null}
     * @param grouping the grouping mode, not {@code null}
     * @return the top-level tree nodes, never {@code null}
     */
    public static List<Object> build(List<SonarIssue> issues, String projectKey, String pathPrefix,
        IssueGrouping grouping)
    {
        if (grouping == IssueGrouping.BY_SEVERITY)
        {
            return buildBySeverity(issues, projectKey, pathPrefix);
        }
        Map<String, List<IssueEntry>> mapped = new TreeMap<>();
        List<IssueEntry> unmapped = new ArrayList<>();
        for (IssueEntry entry : toEntries(issues, projectKey, pathPrefix))
        {
            addToGroup(entry, grouping, mapped, unmapped);
        }
        List<Object> result = new ArrayList<>();
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
     * Builds the three-level by-severity tree: one {@link IssueSuperGroup} per severity, ordered by
     * severity rank (BLOCKER first, INFO last; see {@link #severityRank}), each nesting one
     * {@link IssueGroup} per rule key (ordered alphabetically) whose entries are {@link #sorted(List)}.
     *
     * @param issues the issues, not {@code null}
     * @param projectKey the SonarQube project key, not {@code null}
     * @param pathPrefix the repository path prefix, may be {@code null}
     * @return the severity super-groups, never {@code null}
     */
    private static List<Object> buildBySeverity(List<SonarIssue> issues, String projectKey, String pathPrefix)
    {
        Map<String, List<IssueEntry>> bySeverity =
            new TreeMap<>(Comparator.comparingInt(IssueTreeBuilder::severityRank));
        for (IssueEntry entry : toEntries(issues, projectKey, pathPrefix))
        {
            bySeverity.computeIfAbsent(entry.issue().severity().name(), key -> new ArrayList<>()).add(entry);
        }
        List<Object> result = new ArrayList<>();
        for (Map.Entry<String, List<IssueEntry>> severityGroup : bySeverity.entrySet())
        {
            Map<String, List<IssueEntry>> byRule = new TreeMap<>();
            for (IssueEntry entry : severityGroup.getValue())
            {
                byRule.computeIfAbsent(entry.issue().ruleKey(), key -> new ArrayList<>()).add(entry);
            }
            List<IssueGroup> ruleGroups = new ArrayList<>();
            for (Map.Entry<String, List<IssueEntry>> ruleGroup : byRule.entrySet())
            {
                ruleGroups.add(new IssueGroup(ruleGroup.getKey(), sorted(ruleGroup.getValue())));
            }
            result.add(new IssueSuperGroup(severityGroup.getKey(), ruleGroups));
        }
        return result;
    }

    /**
     * Files a single entry into its group under the requested {@code grouping}, or into {@code unmapped}
     * when grouping by file and the entry has no resolved path (see {@link IssueEntry#relativePath()}).
     *
     * @param entry the entry to file, not {@code null}
     * @param grouping the grouping mode, not {@code null}
     * @param mapped the groups keyed by label, not {@code null}, mutated in place
     * @param unmapped the trailing "not found in project" bucket, not {@code null}, mutated in place
     */
    private static void addToGroup(IssueEntry entry, IssueGrouping grouping, Map<String, List<IssueEntry>> mapped,
        List<IssueEntry> unmapped)
    {
        switch (grouping)
        {
            case BY_RULE -> mapped.computeIfAbsent(entry.issue().ruleKey(), key -> new ArrayList<>()).add(entry);
            case BY_SEVERITY ->
                mapped.computeIfAbsent(entry.issue().severity().name(), key -> new ArrayList<>()).add(entry);
            case BY_FILE ->
            {
                String path = entry.relativePath();
                if (path != null)
                {
                    mapped.computeIfAbsent(path, key -> new ArrayList<>()).add(entry);
                }
                else
                {
                    unmapped.add(entry);
                }
            }
        }
    }

    /**
     * Ranks a severity name for {@link IssueGrouping#BY_SEVERITY} group ordering.
     *
     * @param severityName a {@link SonarSeverity} name, not {@code null}
     * @return the severity's declaration-order rank (BLOCKER lowest, INFO highest)
     */
    private static int severityRank(String severityName)
    {
        return SonarSeverity.valueOf(severityName).ordinal();
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
