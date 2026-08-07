/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.ui.markers;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.eclipse.core.resources.IProject;

import ru.jimmo.edt.sonarq.ui.views.RefreshGeneration;

/**
 * The monotonic version of one project's published issue state, and the fence every marker write is checked
 * against.
 *
 * <p>Marker synchronization is destructive: {@link IssueMarkerSynchronizer#sync} deletes the project's issue
 * markers and recreates them from the entries it was given. The project scheduling rule of
 * {@link MarkerSyncJob} serializes those writes, so no half-written marker set is ever visible - but it says
 * nothing about the <em>age</em> of the data being written. A job queued with an older snapshot (a refresh
 * that finished just before a quick-suppress) and released after a newer one has been published would
 * faithfully overwrite the newer markers with pre-edit line numbers, which is exactly the desynchronization
 * the suppression bookkeeping exists to prevent.
 *
 * <p>Every marker state is therefore published here, and the job that carries a superseded version abandons
 * its run instead of deleting anything. Publication happens in the {@link MarkerSyncJob} constructor - on the
 * producer's thread, at the moment the producer hands its snapshot over - so neither producer (the issues
 * view's {@code scheduleMarkerSync} and {@link ru.jimmo.edt.sonarq.ui.sync.AutoSyncScheduler#syncMarkers})
 * can forget to publish, and neither can publish a version the other does not see.
 *
 * <p>Deliberately the same seam the view already fences its asynchronous results with
 * ({@link RefreshGeneration}), one instance per project rather than one per view, so there is a single
 * generation mechanism in the plug-in instead of two that have to be kept in step.
 */
public final class MarkerStateVersion
{
    /**
     * Versions by project name. Keyed by name rather than by handle because the handle of a project that is
     * deleted and re-created is a different object with the same identity for our purposes; the map is
     * bounded by the number of projects in the workspace and each entry is a single counter.
     */
    private static final ConcurrentMap<String, RefreshGeneration> BY_PROJECT = new ConcurrentHashMap<>();

    private MarkerStateVersion()
    {
    }

    /**
     * Publishes a new marker state for a project and returns its version.
     *
     * @param project the project whose issue state was just produced, not {@code null}
     * @return the version the write must carry, always greater than every version published before it
     */
    public static long publish(IProject project)
    {
        return of(project).start();
    }

    /**
     * Tells whether a write carrying {@code version} may still touch the project's markers.
     *
     * @param project the project the write is about, not {@code null}
     * @param version the version the write carries
     * @return {@code true} when no newer state has been published since
     */
    public static boolean isCurrent(IProject project, long version)
    {
        return of(project).isCurrent(version);
    }

    /**
     * The counter of one project, created on first use.
     *
     * @param project the project, not {@code null}
     * @return the project's counter, never {@code null}
     */
    private static RefreshGeneration of(IProject project)
    {
        return BY_PROJECT.computeIfAbsent(project.getName(), name -> new RefreshGeneration());
    }
}
