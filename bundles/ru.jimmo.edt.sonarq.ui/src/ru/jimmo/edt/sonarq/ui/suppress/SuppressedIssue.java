/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.ui.suppress;

import java.util.Optional;

import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IProject;

import ru.jimmo.edt.sonarq.core.model.IssueSnapshot;
import ru.jimmo.edt.sonarq.core.model.SonarIssue;
import ru.jimmo.edt.sonarq.ui.markers.IssueMarkers;

/**
 * The identity of an issue that was just suppressed from the Problems view, as it travels from the marker the
 * quick fix ran on to the SonarQube Issues view that may hold the same issue in its snapshot.
 *
 * <p>An issue key alone is not that identity. Local-analysis keys are built from rule, file URI, line and
 * column (see {@code ru.jimmo.edt.sonarq.core.localanalysis.SarifParser}) and carry no project at all, and
 * markers exist for every configured project while the view shows one - so two projects that contain the same
 * relative module path (a base configuration and its copy, a project and its extension) produce equal keys for
 * different findings in different files. Suppressing in one of them then shifted the other's snapshot: line
 * numbers moved for a file that was never edited, and the next suppression from the view wrapped the wrong
 * lines. Carrying the marker's project with the key, and requiring both to match, is what keeps one project's
 * edit inside that project.
 *
 * @param project the project of the file the quick fix edited, not {@code null}
 * @param issueKey the {@link SonarIssue#key()} recorded on the marker, not {@code null}; empty when the marker
 *     carries no issue key, which makes this notification unusable
 */
public record SuppressedIssue(IProject project, String issueKey)
{
    /**
     * Reads the identity off the marker a quick fix was applied to.
     *
     * @param marker the resolved marker, not {@code null}
     * @return the identity, or {@code null} when the marker is not on a resource inside a project
     */
    public static SuppressedIssue of(IMarker marker)
    {
        IProject project = marker.getResource().getProject();
        if (project == null)
        {
            return null;
        }
        return new SuppressedIssue(project, marker.getAttribute(IssueMarkers.ATTR_ISSUE_KEY, "")); //$NON-NLS-1$
    }

    /**
     * Finds the issue this suppression refers to inside a view's snapshot.
     *
     * @param viewProject the project the snapshot was loaded for, may be {@code null} when the view is bound
     *     to no project
     * @param snapshot the view's current snapshot, may be {@code null} when it holds none
     * @return the issue to renumber the snapshot around, or {@link Optional#empty()} when this suppression
     *     belongs to another project, carries no key, or names an issue the snapshot does not hold (a refresh
     *     has replaced it since)
     */
    public Optional<SonarIssue> locateIn(IProject viewProject, IssueSnapshot snapshot)
    {
        if (issueKey.isEmpty() || snapshot == null || !project.equals(viewProject))
        {
            return Optional.empty();
        }
        return snapshot.issues().stream().filter(issue -> issueKey.equals(issue.key())).findFirst();
    }
}
