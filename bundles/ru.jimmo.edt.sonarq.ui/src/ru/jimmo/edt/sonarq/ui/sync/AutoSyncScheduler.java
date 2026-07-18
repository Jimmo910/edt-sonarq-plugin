/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.ui.sync;

import java.util.List;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.core.runtime.preferences.IPreferencesService;

import ru.jimmo.edt.sonarq.ui.Messages;
import ru.jimmo.edt.sonarq.ui.SonarqPlugin;
import ru.jimmo.edt.sonarq.ui.markers.IssueMarkerSynchronizer;
import ru.jimmo.edt.sonarq.ui.markers.MarkerSyncResult;
import ru.jimmo.edt.sonarq.ui.settings.PreferenceConstants;
import ru.jimmo.edt.sonarq.ui.views.IssueEntry;
import ru.jimmo.edt.sonarq.ui.views.IssueTreeBuilder;
import ru.jimmo.edt.sonarq.ui.views.RefreshIssuesJob;
import ru.jimmo.edt.sonarq.ui.views.RefreshResult;

/**
 * Owns the single background job that periodically refreshes SonarQube issues (and, when enabled, the
 * editor markers) of every open workspace project.
 *
 * <p>All state transitions are serialized on the class monitor. A monotonic generation counter fences
 * stale timer chains: a job only re-schedules itself while it is still the current job of the current
 * generation, so an {@link #applyPreferences()} call that cancels and replaces the job reliably stops
 * the previous timer chain.
 */
public final class AutoSyncScheduler
{
    private static final long MILLIS_PER_MINUTE = 60_000L;

    private static Job currentJob;

    private static long generation;

    private AutoSyncScheduler()
    {
    }

    /**
     * Reconciles the background refresh job with the current preferences.
     *
     * <p>Cancels any running job, then, when background auto-sync is enabled, schedules a fresh job with
     * a first delay equal to the configured interval. The first run is deliberately delayed rather than
     * immediate so that EDT start-up does not trigger a burst of network traffic.
     */
    public static synchronized void applyPreferences()
    {
        generation++;
        if (currentJob != null)
        {
            currentJob.cancel();
            currentJob = null;
        }
        IPreferencesService service = Platform.getPreferencesService();
        boolean enabled =
            service.getBoolean(SonarqPlugin.PLUGIN_ID, PreferenceConstants.PREF_AUTO_SYNC, false, null);
        if (!enabled)
        {
            return;
        }
        int minutes = service.getInt(SonarqPlugin.PLUGIN_ID, PreferenceConstants.PREF_AUTO_SYNC_MINUTES,
            PreferenceConstants.DEFAULT_AUTO_SYNC_MINUTES, null);
        long intervalMillis = delayMillis(minutes);
        AutoSyncJob job = new AutoSyncJob(generation, intervalMillis);
        currentJob = job;
        job.schedule(intervalMillis);
    }

    /**
     * Cancels the recurring job and fences its generation so it cannot reschedule. Called on plug-in stop.
     */
    public static synchronized void stop()
    {
        generation++;
        if (currentJob != null)
        {
            currentJob.cancel();
            currentJob = null;
        }
    }

    /**
     * Converts a preference interval expressed in minutes to milliseconds, flooring it at one minute so a
     * misconfigured (zero or negative) preference value cannot degenerate into a busy-reschedule loop.
     *
     * @param minutes the configured interval in minutes
     * @return the interval in milliseconds, never less than {@value #MILLIS_PER_MINUTE}
     */
    private static long delayMillis(int minutes)
    {
        return Math.max(1, minutes) * MILLIS_PER_MINUTE;
    }

    private static synchronized void rescheduleIfActive(Job job, long jobGeneration, long intervalMillis)
    {
        if (job != currentJob || jobGeneration != generation)
        {
            return;
        }
        boolean enabled = Platform.getPreferencesService()
            .getBoolean(SonarqPlugin.PLUGIN_ID, PreferenceConstants.PREF_AUTO_SYNC, false, null);
        if (enabled)
        {
            job.schedule(intervalMillis);
        }
    }

    private static void refreshAllProjects()
    {
        String mode = Platform.getPreferencesService().getString(SonarqPlugin.PLUGIN_ID,
            PreferenceConstants.PREF_MODE, PreferenceConstants.MODE_SERVER, null);
        if (PreferenceConstants.MODE_LOCAL.equals(mode))
        {
            // Local analysis is a heavyweight per-project language-server run; never trigger it from the
            // background timer. The job still reschedules, so switching back to server mode resumes syncing.
            Platform.getLog(AutoSyncScheduler.class)
                .info("Background auto-sync skipped: local analysis runs only on an explicit refresh."); //$NON-NLS-1$
            return;
        }
        for (IProject project : ResourcesPlugin.getWorkspace().getRoot().getProjects())
        {
            if (project.isOpen() && !runRefresh(project))
            {
                // Interrupted: stop this cycle. The scheduler's generation fence handles rescheduling.
                return;
            }
        }
    }

    /**
     * Runs one project's refresh to completion before returning, so a whole cycle finishes before the
     * timer reschedules and slow refreshes cannot overlap or let a stale response overwrite newer markers.
     *
     * @param project the open workspace project to refresh, not {@code null}
     * @return {@code true} to continue the cycle, {@code false} if the thread was interrupted while waiting
     */
    private static boolean runRefresh(IProject project)
    {
        var inputs = RefreshInputsFactory.create(project);
        if (inputs.isEmpty())
        {
            return true;
        }
        ProjectRefreshInputs refresh = inputs.get();
        Job job = new RefreshIssuesJob(refresh.provider(), refresh.project(), refresh.binding(), null,
            result -> onRefreshed(refresh, result));
        job.schedule();
        try
        {
            job.join();
            return true;
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static void onRefreshed(ProjectRefreshInputs inputs, RefreshResult result)
    {
        if (result.isError())
        {
            Platform.getLog(AutoSyncScheduler.class).warn(result.errorMessage());
            return;
        }
        boolean showMarkers = Platform.getPreferencesService()
            .getBoolean(SonarqPlugin.PLUGIN_ID, PreferenceConstants.PREF_SHOW_MARKERS, true, null);
        if (!showMarkers)
        {
            return;
        }
        try
        {
            List<IssueEntry> entries = IssueTreeBuilder.toEntries(result.snapshot().issues(),
                inputs.mappingProjectKey(), inputs.mappingPathPrefix());
            MarkerSyncResult syncResult = new IssueMarkerSynchronizer().sync(inputs.project(), entries);
            if (syncResult.missingFile() > 0)
            {
                Platform.getLog(AutoSyncScheduler.class).warn(syncResult.missingFile()
                    + " issue(s) resolved to a project file that does not exist even after a workspace " //$NON-NLS-1$
                    + "refresh; they are not shown as Problems-view markers"); //$NON-NLS-1$
            }
        }
        catch (CoreException | RuntimeException e)
        {
            Platform.getLog(AutoSyncScheduler.class).warn(e.getMessage(), e);
        }
    }

    /** The recurring background refresh job; re-schedules itself while it stays the active generation. */
    private static final class AutoSyncJob extends Job
    {
        private final long jobGeneration;

        private final long intervalMillis;

        AutoSyncJob(long jobGeneration, long intervalMillis)
        {
            super(Messages.AutoSyncJob_Name);
            this.jobGeneration = jobGeneration;
            this.intervalMillis = intervalMillis;
            setSystem(true);
        }

        @Override
        protected IStatus run(IProgressMonitor monitor)
        {
            try
            {
                refreshAllProjects();
            }
            catch (RuntimeException e)
            {
                Platform.getLog(AutoSyncScheduler.class).warn(e.getMessage(), e);
            }
            rescheduleIfActive(this, jobGeneration, intervalMillis);
            return Status.OK_STATUS;
        }
    }
}
