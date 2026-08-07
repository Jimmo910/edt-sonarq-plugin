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
import ru.jimmo.edt.sonarq.ui.resources.IssueAnchors;
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
     *     when it failed, not {@code null}
     */
    public MarkerSyncJob(IProject project, Supplier<List<IssueEntry>> entries,
        Consumer<MarkerSyncResult> onSynced)
    {
        super(Messages.MarkerSyncJob_Name);
        this.project = project;
        this.entries = entries;
        this.onSynced = onSynced;
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
            // Anchoring belongs here rather than in either caller: this is the one background job every
            // marker synchronization goes through - the issues view's and the unattended auto-sync's alike -
            // and the markers it writes are what the Problems-view quick fix verifies its line against when
            // no view is open at all. It reads each referenced file once, skips issues that are already
            // anchored (the view's refresh anchors its snapshot before it gets here), and must run before
            // IssueMarkerSynchronizer#sync, whose first act is to delete the markers whose anchors it
            // carries over.
            List<IssueEntry> anchored = IssueAnchors.anchor(project, entries.get());
            MarkerSyncResult result = new IssueMarkerSynchronizer().sync(project, anchored);
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
