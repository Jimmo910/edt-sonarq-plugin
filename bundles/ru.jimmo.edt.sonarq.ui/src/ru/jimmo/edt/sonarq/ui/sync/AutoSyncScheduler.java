/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.ui.sync;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.core.runtime.preferences.IPreferencesService;

import ru.jimmo.edt.sonarq.ui.Messages;
import ru.jimmo.edt.sonarq.ui.SonarqPlugin;
import ru.jimmo.edt.sonarq.ui.markers.MarkerSyncJob;
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

    private static boolean localModeSkipLogged;

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
        localModeSkipLogged = false;
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
        localModeSkipLogged = false;
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

    /**
     * Enters the "skipping because the plug-in is in local-analysis mode" state, and tells whether that is
     * a change worth logging.
     *
     * <p>The timer keeps rescheduling while in local mode - by default every 15 minutes, for as long as EDT
     * runs - so logging the skip on every cycle would fill the workspace log with one identical INFO line
     * forever (review minor M12). The condition is logged once, when it first holds, and again only after
     * {@link #leaveLocalModeSkip()} or a scheduler state change ({@link #applyPreferences()},
     * {@link #stop()}) has re-armed it, so a genuinely new occurrence is never silenced.
     *
     * @return {@code true} when the caller should log the skip
     */
    static synchronized boolean enterLocalModeSkip()
    {
        if (localModeSkipLogged)
        {
            return false;
        }
        localModeSkipLogged = true;
        return true;
    }

    /** Re-arms the local-mode skip log, so returning to local mode later is reported again. */
    static synchronized void leaveLocalModeSkip()
    {
        localModeSkipLogged = false;
    }

    private static void refreshAllProjects()
    {
        String mode = Platform.getPreferencesService().getString(SonarqPlugin.PLUGIN_ID,
            PreferenceConstants.PREF_MODE, PreferenceConstants.MODE_SERVER, null);
        if (PreferenceConstants.MODE_LOCAL.equals(mode))
        {
            // Local analysis is a heavyweight per-project language-server run; never trigger it from the
            // background timer. The job still reschedules, so switching back to server mode resumes syncing.
            if (enterLocalModeSkip())
            {
                Platform.getLog(AutoSyncScheduler.class).info(
                    "Background auto-sync skipped: local analysis runs only on an explicit refresh."); //$NON-NLS-1$
            }
            return;
        }
        leaveLocalModeSkip();
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
     * Runs one project's refresh, and then its marker synchronization, to completion before returning, so a
     * whole cycle finishes before the timer reschedules and slow refreshes cannot overlap or let a stale
     * response overwrite newer markers.
     *
     * <p>The refresh job only records its result here; everything that follows runs in this thread, after
     * {@link Job#join()}, i.e. once the refresh job and its {@link ProjectAnalysisRule} are gone. Doing the
     * marker work in the job's callback instead would run it under that rule - see {@link #syncMarkers}.
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
        AtomicReference<RefreshResult> refreshed = new AtomicReference<>();
        Job job = new RefreshIssuesJob(refresh.provider(), refresh.project(), refresh.binding(), null,
            refreshed::set);
        job.schedule();
        try
        {
            job.join();
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            return false;
        }
        return onRefreshed(refresh, refreshed.get());
    }

    /**
     * Handles one finished refresh: logs a failed one, and otherwise synchronizes the project's markers
     * unless the user disabled them - the same gate the issues view applies before it schedules its own
     * synchronization.
     *
     * @param inputs the refreshed project's inputs, not {@code null}
     * @param result the refresh outcome, or {@code null} when the refresh was cancelled before reporting one
     * @return {@code true} to continue the cycle, {@code false} if the thread was interrupted while waiting
     */
    private static boolean onRefreshed(ProjectRefreshInputs inputs, RefreshResult result)
    {
        if (result == null)
        {
            // Cancelled: RefreshIssuesJob returns without ever calling the callback. Nothing to synchronize.
            return true;
        }
        if (result.isError())
        {
            Platform.getLog(AutoSyncScheduler.class).warn(result.errorMessage());
            return true;
        }
        boolean showMarkers = Platform.getPreferencesService()
            .getBoolean(SonarqPlugin.PLUGIN_ID, PreferenceConstants.PREF_SHOW_MARKERS, true, null);
        if (!showMarkers)
        {
            return true;
        }
        return syncMarkers(inputs.project(), () -> IssueTreeBuilder.toEntries(result.snapshot().issues(),
            inputs.mappingProjectKey(), inputs.mappingPathPrefix()));
    }

    /**
     * Replaces one project's issue markers in a {@link MarkerSyncJob} of its own and waits for that job.
     *
     * <p>The synchronization must not run in the calling thread while that thread executes a job holding a
     * {@link ProjectAnalysisRule} - as the refresh job's callback does:
     * {@code IssueMarkerSynchronizer#sync} begins the project's resource rule, which the analysis rule does
     * not contain, so the job manager rejects it with an {@link IllegalArgumentException} and no marker is
     * ever updated. Handing the work to a separate job scoped to the project - exactly what
     * {@code SonarIssuesView#scheduleMarkerSync} does - keeps it outside the analysis rule.
     *
     * <p>Waiting for that job preserves {@link #runRefresh}'s "one whole cycle at a time" contract. It is
     * safe from the auto-sync job, which holds no scheduling rule of its own, and from any caller whose rule
     * does not conflict with the project's resource rule.
     *
     * @param project the project whose markers are replaced, not {@code null}
     * @param entries supplies the entries to materialize, evaluated in the synchronization job, not
     *     {@code null}
     * @return {@code true} when the synchronization finished, {@code false} if the thread was interrupted
     *     while waiting
     */
    static boolean syncMarkers(IProject project, Supplier<List<IssueEntry>> entries)
    {
        MarkerSyncJob job = new MarkerSyncJob(project, entries);
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
