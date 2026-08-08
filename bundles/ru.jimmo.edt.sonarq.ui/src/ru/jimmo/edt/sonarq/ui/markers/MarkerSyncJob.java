/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.ui.markers;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.WorkspaceJob;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;

import ru.jimmo.edt.sonarq.ui.Messages;
import ru.jimmo.edt.sonarq.ui.views.IssueEntry;

/**
 * The single job that replaces one project's issue markers, scheduled on that project's own resource rule.
 *
 * <p>Every marker synchronization goes through a job of this kind, because
 * {@link IssueMarkerSynchronizer#sync} begins the project's resource rule and the Eclipse job manager only
 * allows that when the rule of the job running on the current thread contains it (otherwise it throws
 * {@code IllegalArgumentException}, "does not match outer scope rule"). The refresh job runs under
 * {@code ru.jimmo.edt.sonarq.ui.sync.ProjectAnalysisRule}, which deliberately conflicts with nothing but
 * itself and therefore contains no resource rule, so neither the issues view nor the background auto-sync
 * may synchronize markers from the refresh callback: both hand the work to this job instead.
 *
 * <p>That rule is what keeps the marker writes from interleaving; it is not what keeps them <em>in order</em>.
 * Every job carries the version of the issue state it is about to write ({@link MarkerStateVersion}) and
 * verifies it under the rule before the synchronization deletes anything, so a job released after a newer
 * state has been published abandons its run instead of resurrecting the older one.
 *
 * <p>Which version it carries is the whole of the guarantee. A job derived from a refresh must be given the
 * version that refresh reserved <em>before</em> it fetched its issues; publishing one here instead - which is
 * what the constructors below still do for callers that have no earlier moment to point at, such as a
 * quick-suppress that has just edited the file - would mint an always-current version at the end of a fetch
 * and wave through exactly the stale write the fence exists to stop.
 */
public final class MarkerSyncJob extends WorkspaceJob
{
    /** Callback for callers that only want the markers updated; the log already records anything notable. */
    private static final Consumer<MarkerSyncResult> IGNORE_RESULT = result ->
    {
        // Nothing to report back.
    };

    private final IProject project;

    private final Supplier<List<IssueEntry>> entries;

    private final Consumer<MarkerSyncResult> onSynced;

    private final long stateVersion;

    /**
     * Creates a synchronization job whose outcome is only written to the log.
     *
     * @param project the project whose issue markers are replaced, not {@code null}
     * @param entries supplies the entries to materialize as markers; evaluated in the job thread so a large
     *     snapshot is never mapped on the caller's thread, not {@code null}
     */
    public MarkerSyncJob(IProject project, Supplier<List<IssueEntry>> entries)
    {
        this(project, entries, IGNORE_RESULT);
    }

    /**
     * Creates a synchronization job that reports its outcome to a callback.
     *
     * @param project the project whose issue markers are replaced, not {@code null}
     * @param entries supplies the entries to materialize as markers; evaluated in the job thread so a large
     *     snapshot is never mapped on the caller's thread, not {@code null}
     * @param onSynced receives the outcome in the job thread once the synchronization succeeded; not called
     *     when it failed, and not called when the run was abandoned because a newer marker state had been
     *     published in the meantime - its counts would describe a state that was never written, not
     *     {@code null}
     */
    public MarkerSyncJob(IProject project, Supplier<List<IssueEntry>> entries,
        Consumer<MarkerSyncResult> onSynced)
    {
        // Published here, on the producer's thread, at the moment it hands its snapshot over: for a producer
        // whose state was created just now - a quick-suppress - this <em>is</em> the moment the state came
        // into being. A producer whose state predates its own completion must use the overload below.
        this(project, entries, onSynced, MarkerStateVersion.publish(project));
    }

    /**
     * Creates a synchronization job for an issue state that was published earlier.
     *
     * @param project the project whose issue markers are replaced, not {@code null}
     * @param entries supplies the entries to materialize as markers; evaluated in the job thread, not
     *     {@code null}
     * @param onSynced receives the outcome in the job thread once the synchronization succeeded, not
     *     {@code null}
     * @param stateVersion the version the state being written was published under - for a refresh, the one it
     *     reserved before it fetched anything (see {@code RefreshResult#markerStateVersion()})
     */
    public MarkerSyncJob(IProject project, Supplier<List<IssueEntry>> entries,
        Consumer<MarkerSyncResult> onSynced, long stateVersion)
    {
        super(Messages.MarkerSyncJob_Name);
        this.project = project;
        this.entries = entries;
        this.onSynced = onSynced;
        this.stateVersion = stateVersion;
        // The project's resource rule contains the rule IssueMarkerSynchronizer#sync begins, which makes the
        // synchronization legal here, and serializes it with other resource work on the same project.
        setRule(project);
        setSystem(true);
    }

    @Override
    public IStatus runInWorkspace(IProgressMonitor monitor)
    {
        try
        {
            if (!MarkerStateVersion.isCurrent(project, stateVersion))
            {
                // Superseded before this job even started: skip mapping the snapshot. The decision that
                // matters is repeated inside the synchronization, under the project's rule.
                return Status.OK_STATUS;
            }
            // The entries arrive already anchored: the refresh that produced them reconciled them against the
            // plug-in's persisted anchor memory (see ru.jimmo.edt.sonarq.ui.views.RefreshIssuesJob), which
            // this job used to duplicate with a second, memory-less pass of its own. The anchors written onto
            // the markers below are therefore the same fingerprints the issues view holds, and the
            // Problems-view quick fix verifies against the same evidence whether or not a view is open.
            MarkerSyncResult result = new IssueMarkerSynchronizer().sync(project, entries.get(),
                () -> MarkerStateVersion.isCurrent(project, stateVersion));
            if (result.abandoned())
            {
                // A newer state won the race for the project's rule; its markers are on screen and must not
                // be replaced by this job's older ones. Nothing was written, so there is nothing to report.
                return Status.OK_STATUS;
            }
            if (result.missingFile() > 0)
            {
                Platform.getLog(MarkerSyncJob.class).warn(result.missingFile()
                    + " issue(s) resolved to a project file that does not exist even after a workspace " //$NON-NLS-1$
                    + "refresh; they are not shown as Problems-view markers"); //$NON-NLS-1$
            }
            onSynced.accept(result);
        }
        catch (CoreException | RuntimeException e)
        {
            Platform.getLog(MarkerSyncJob.class).warn(e.getMessage(), e);
        }
        return Status.OK_STATUS;
    }
}
