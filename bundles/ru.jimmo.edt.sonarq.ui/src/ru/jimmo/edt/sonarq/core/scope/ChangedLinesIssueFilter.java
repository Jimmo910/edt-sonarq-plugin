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
        String prefix = relativePrefix(changed.workTreeRoot().toPath(), projectRoot);
        return issues.stream().filter(issue -> keep(issue, projectKey, prefix, changed)).toList();
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
     * @param workTreeRoot the git work tree root, not {@code null}
     * @param projectRoot the project location, not {@code null}
     * @return the relative prefix, empty when the project is the work tree root
     */
    private static String relativePrefix(Path workTreeRoot, Path projectRoot)
    {
        Path rel = workTreeRoot.relativize(projectRoot);
        String text = rel.toString().replace('\\', '/');
        return text.equals(".") ? EMPTY : text; //$NON-NLS-1$
    }
}
