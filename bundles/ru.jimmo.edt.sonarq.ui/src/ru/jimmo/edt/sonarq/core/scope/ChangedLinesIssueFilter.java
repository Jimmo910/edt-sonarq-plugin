/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.core.scope;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import ru.jimmo.edt.sonarq.core.mapping.ComponentPathMapper;
import ru.jimmo.edt.sonarq.core.model.SonarIssue;

/** Keeps only issues that fall on lines changed relative to a base branch. */
public final class ChangedLinesIssueFilter
{
    private static final String EMPTY = ""; //$NON-NLS-1$

    private ChangedLinesIssueFilter()
    {
    }

    /**
     * Filters {@code issues} down to those on changed lines. Degrades to returning {@code issues}
     * unchanged when {@code changed} is not {@link ChangedLines#available() available}.
     *
     * @param issues the parsed issues, not {@code null}
     * @param projectKey the project key that prefixes component keys, not {@code null}
     * @param projectRoot the EDT project location, not {@code null}
     * @param changed the changed-lines lookup, not {@code null}
     * @return the retained issues, never {@code null}
     */
    public static List<SonarIssue> keepChanged(List<SonarIssue> issues, String projectKey, Path projectRoot,
        ChangedLines changed)
    {
        if (!changed.available())
        {
            return issues;
        }
        Optional<String> prefix = relativePrefix(changed.workTreeRoot().toPath(), projectRoot);
        // Fail open exactly like the !available() branch above: a prefix we cannot resolve must never be
        // treated as "nothing matches", or every issue would silently vanish.
        if (prefix.isEmpty())
        {
            return issues;
        }
        String prefixValue = prefix.get();
        return issues.stream().filter(issue -> keep(issue, projectKey, prefixValue, changed)).toList();
    }

    /**
     * Decides whether one issue is on a changed line (fail-open when its path cannot be mapped).
     *
     * @param issue the issue, not {@code null}
     * @param projectKey the project key, not {@code null}
     * @param prefix the project location relative to the work tree ({@code /}-separated, may be empty)
     * @param changed the changed-lines lookup, not {@code null}
     * @return {@code true} to keep the issue
     */
    private static boolean keep(SonarIssue issue, String projectKey, String prefix, ChangedLines changed)
    {
        Optional<String> relative = ComponentPathMapper.toProjectRelativePath(issue.componentKey(),
            projectKey, EMPTY);
        if (relative.isEmpty())
        {
            return true;
        }
        String repoPath = prefix.isEmpty() ? relative.get() : prefix + '/' + relative.get();
        return changed.isChanged(repoPath, issue.line());
    }

    /**
     * Computes {@code projectRoot} relative to {@code workTreeRoot} as a {@code /}-separated prefix.
     *
     * <p>Returns {@link Optional#empty()} when the prefix cannot be resolved: {@code relativize} throws
     * (e.g. different roots/drive letters on Windows) or the project root is not under the work tree at all
     * (a {@code ..}-leading result) - a linked worktree, a {@code core.worktree} override, or a
     * junction/symlink can all put {@code projectRoot} outside {@code workTreeRoot}. Callers must treat an
     * empty result as fail-open, never as an empty (root-level) prefix.
     *
     * @param workTreeRoot the git work tree root, not {@code null}
     * @param projectRoot the project location, not {@code null}
     * @return the relative prefix (empty string when the project is the work tree root), or
     *     {@link Optional#empty()} when unresolvable
     */
    private static Optional<String> relativePrefix(Path workTreeRoot, Path projectRoot)
    {
        Path rel;
        try
        {
            rel = workTreeRoot.relativize(projectRoot);
        }
        catch (IllegalArgumentException e)
        {
            return Optional.empty();
        }
        if (rel.getNameCount() > 0 && "..".equals(rel.getName(0).toString())) //$NON-NLS-1$
        {
            return Optional.empty();
        }
        return Optional.of(rel.toString().replace('\\', '/'));
    }
}
