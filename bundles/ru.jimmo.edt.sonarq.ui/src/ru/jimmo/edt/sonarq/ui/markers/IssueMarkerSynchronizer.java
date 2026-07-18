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
     * <p>May be called from any thread: the project refresh, marker deletion and marker creation for the
     * whole project are wrapped in a single {@link IWorkspaceRunnable}, so callers do not need to switch to
     * a UI or worker thread themselves.</p>
     *
     * <p>Refreshes the project's resource tree ({@link IProject#refreshLocal}) before touching any marker,
     * so a file that already exists on disk (e.g. written by a local analysis run) but was not yet picked up
     * by the workspace is recognized instead of being wrongly reported as missing (issue #6).</p>
     *
     * @param project the EDT project whose markers are replaced, not {@code null}
     * @param entries the issue entries to materialize as markers, not {@code null}; an entry whose
     *     {@link IssueEntry#relativePath()} is {@code null} is skipped and counted in neither field of the
     *     returned {@link MarkerSyncResult} - its component never mapped to a project path at all (see
     *     {@link ru.jimmo.edt.sonarq.ui.views.IssueTreeBuilder#countUnmapped})
     * @return the created-versus-missing-file marker counts, never {@code null}
     * @throws CoreException when the workspace operation fails
     */
    public MarkerSyncResult sync(IProject project, List<IssueEntry> entries) throws CoreException
    {
        int[] created = new int[1];
        int[] missingFile = new int[1];
        IWorkspaceRunnable runnable = monitor ->
        {
            project.refreshLocal(IResource.DEPTH_INFINITE, monitor);
            project.deleteMarkers(IssueMarkers.MARKER_TYPE, true, IResource.DEPTH_INFINITE);
            for (IssueEntry entry : entries)
            {
                switch (createMarker(project, entry))
                {
                    case CREATED -> created[0]++;
                    case MISSING_FILE -> missingFile[0]++;
                    case SKIPPED_UNMAPPED ->
                    {
                        // No project path at all; not this method's concern, see countUnmapped.
                    }
                }
            }
        };
        project.getWorkspace().run(runnable, project, IWorkspace.AVOID_UPDATE, null);
        return new MarkerSyncResult(created[0], missingFile[0]);
    }

    /**
     * Removes all issue markers from every resource in the workspace.
     *
     * <p>The deletion is wrapped in an {@link IWorkspaceRunnable} scheduled on the workspace root rule, so
     * it serializes with any in-flight {@link #sync(IProject, List)} call (whose project rule is contained
     * within the root rule) instead of racing it.</p>
     *
     * @throws CoreException when the marker deletion fails
     */
    public void clearAll() throws CoreException
    {
        ResourcesPlugin.getWorkspace().run(
            monitor -> ResourcesPlugin.getWorkspace().getRoot()
                .deleteMarkers(IssueMarkers.MARKER_TYPE, true, IResource.DEPTH_INFINITE),
            ResourcesPlugin.getWorkspace().getRoot(), IWorkspace.AVOID_UPDATE, null);
    }

    private static MarkerOutcome createMarker(IProject project, IssueEntry entry) throws CoreException
    {
        String relativePath = entry.relativePath();
        if (relativePath == null)
        {
            return MarkerOutcome.SKIPPED_UNMAPPED;
        }
        IFile file = project.getFile(relativePath);
        if (!file.exists())
        {
            return MarkerOutcome.MISSING_FILE;
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
        return MarkerOutcome.CREATED;
    }

    /** The outcome of a single {@link #createMarker} call. */
    private enum MarkerOutcome
    {
        /** A marker was created for the entry. */
        CREATED,
        /** The entry resolved to a project path, but no file exists there even after the refresh. */
        MISSING_FILE,
        /** The entry's component never mapped to a project path; {@link #sync} does not count this. */
        SKIPPED_UNMAPPED
    }
}
