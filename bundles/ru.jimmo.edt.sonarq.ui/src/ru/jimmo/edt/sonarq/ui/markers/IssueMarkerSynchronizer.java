/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.ui.markers;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.IWorkspaceRunnable;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;

import ru.jimmo.edt.sonarq.core.model.SonarIssue;
import ru.jimmo.edt.sonarq.ui.views.IssueEntry;

/**
 * Synchronizes the transient {@value IssueMarkers#MARKER_TYPE} markers on workspace files with a list
 * of SonarQube issue entries.
 */
public final class IssueMarkerSynchronizer
{
    /**
     * Replaces all issue markers on the given project with markers derived from the given entries.
     *
     * <p>May be called from any thread: the marker deletion and creation for the whole project are wrapped
     * in a single {@link IWorkspaceRunnable}, so callers do not need to switch to a UI or worker thread
     * themselves.</p>
     *
     * @param project the EDT project whose markers are replaced, not {@code null}
     * @param entries the issue entries to materialize as markers, not {@code null}; an entry is skipped
     *     when its {@link IssueEntry#relativePath()} is {@code null} or does not resolve to an existing
     *     {@link IFile} in the project
     * @throws CoreException when the workspace operation fails
     */
    public void sync(IProject project, List<IssueEntry> entries) throws CoreException
    {
        IWorkspaceRunnable runnable = monitor ->
        {
            project.deleteMarkers(IssueMarkers.MARKER_TYPE, true, IResource.DEPTH_INFINITE);
            for (IssueEntry entry : entries)
            {
                createMarker(project, entry);
            }
        };
        project.getWorkspace().run(runnable, project, IWorkspace.AVOID_UPDATE, null);
    }

    /**
     * Removes all issue markers from every resource in the workspace.
     *
     * @throws CoreException when the marker deletion fails
     */
    public void clearAll() throws CoreException
    {
        ResourcesPlugin.getWorkspace().getRoot()
            .deleteMarkers(IssueMarkers.MARKER_TYPE, true, IResource.DEPTH_INFINITE);
    }

    private static void createMarker(IProject project, IssueEntry entry) throws CoreException
    {
        String relativePath = entry.relativePath();
        if (relativePath == null)
        {
            return;
        }
        IFile file = project.getFile(relativePath);
        if (!file.exists())
        {
            return;
        }
        SonarIssue issue = entry.issue();
        Map<String, Object> attributes = new HashMap<>();
        attributes.put(IMarker.MESSAGE, "[" + issue.ruleKey() + "] " + issue.message()); //$NON-NLS-1$ //$NON-NLS-2$
        attributes.put(IMarker.SEVERITY, IssueMarkers.toMarkerSeverity(issue.severity()));
        if (issue.line() > 0)
        {
            attributes.put(IMarker.LINE_NUMBER, issue.line());
        }
        attributes.put(IssueMarkers.ATTR_RULE_KEY, issue.ruleKey());
        attributes.put(IssueMarkers.ATTR_SONAR_SEVERITY, issue.severity().name());
        attributes.put(IssueMarkers.ATTR_ISSUE_KEY, issue.key());
        IMarker marker = file.createMarker(IssueMarkers.MARKER_TYPE);
        marker.setAttributes(attributes);
    }
}
