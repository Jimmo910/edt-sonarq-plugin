/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.ui.markers;

/**
 * The outcome of an {@link IssueMarkerSynchronizer#sync} call.
 *
 * @param created the number of markers created, one per entry whose resolved path is an existing file
 *     in the project
 * @param missingFile the number of entries with a non-{@code null} relative path (see
 *     {@link ru.jimmo.edt.sonarq.ui.views.IssueEntry#relativePath()}) whose file does not exist in the
 *     project even after that path was refreshed from disk ({@link
 *     org.eclipse.core.resources.IResource#refreshLocal}) - i.e.
 *     issues that were mapped to a project path but still cannot show as a Problems-view marker. This is
 *     distinct from an entry whose component never mapped to a path at all, which
 *     {@link ru.jimmo.edt.sonarq.ui.views.IssueTreeBuilder#countUnmapped} already tracks and which this
 *     count does not include
 * @param abandoned {@code true} when the synchronization wrote nothing at all because a newer marker state
 *     had already been published for the project (see {@link MarkerStateVersion}); the two counts are then
 *     zero and describe nothing, and callers must not report them as the project's current state
 */
public record MarkerSyncResult(int created, int missingFile, boolean abandoned)
{
    /**
     * A completed synchronization.
     *
     * @param created the number of markers created
     * @param missingFile the number of entries whose file does not exist in the project
     */
    public MarkerSyncResult(int created, int missingFile)
    {
        this(created, missingFile, false);
    }

    /**
     * The outcome of a run that was fenced off by a newer marker state and therefore touched no marker.
     *
     * @return the abandoned outcome, never {@code null}
     */
    public static MarkerSyncResult superseded()
    {
        return new MarkerSyncResult(0, 0, true);
    }
}
