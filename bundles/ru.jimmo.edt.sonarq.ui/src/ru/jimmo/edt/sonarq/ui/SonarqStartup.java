/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.ui;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.core.runtime.preferences.IEclipsePreferences.IPreferenceChangeListener;
import org.eclipse.core.runtime.preferences.IEclipsePreferences.PreferenceChangeEvent;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.ui.IStartup;

import ru.jimmo.edt.sonarq.ui.markers.IssueMarkerSynchronizer;
import ru.jimmo.edt.sonarq.ui.settings.PreferenceConstants;
import ru.jimmo.edt.sonarq.ui.sync.AutoSyncScheduler;

/**
 * Wires the background auto-sync to the workbench life cycle: it primes the scheduler on early start-up
 * and keeps it in sync with later preference changes.
 *
 * <p>The {@link #listener} field is static and every access to it - installation, the watch-state query and
 * teardown - is guarded by the same monitor, the class one. {@link #earlyStartup()} used to be an
 * <em>instance</em>-synchronized method writing that static field while {@link #isWatchingPreferences()} and
 * {@link #shutdown()} synchronized on the class: two different locks over one piece of state, which happens
 * to be harmless only because the workbench calls early start-up once (review minor M4).
 */
public final class SonarqStartup implements IStartup
{
    private static IPreferenceChangeListener listener;

    @Override
    public void earlyStartup()
    {
        install();
    }

    /**
     * Arms the auto-sync scheduler and registers the preference listener, unless one is already registered.
     *
     * <p>Static and synchronized on the class, like every other access to {@link #listener}. Idempotent: a
     * second call while a listener is live would otherwise register a duplicate that {@link #shutdown()}
     * could never remove.
     */
    private static synchronized void install()
    {
        if (listener != null)
        {
            return;
        }
        AutoSyncScheduler.applyPreferences();
        listener = SonarqStartup::onPreferenceChange;
        InstanceScope.INSTANCE.getNode(SonarqPlugin.PLUGIN_ID).addPreferenceChangeListener(listener);
    }

    /**
     * Tells whether this class is currently watching the plug-in's preferences, i.e. whether early start-up
     * has run and has not been shut down since.
     *
     * <p>The auto-sync scheduler is normally armed from {@link #earlyStartup()} and re-armed by the listener
     * that method registers. A user can switch this plug-in's early start-up off (Preferences &gt; General &gt;
     * Startup and Shutdown), and then neither happens: toggling the auto-sync preference would have no effect
     * until the next EDT restart. The preference page therefore applies the scheduler itself, but only when
     * this returns {@code false} - when the listener is live it already does exactly that, and arming twice
     * would cancel and reschedule the freshly armed job for nothing.
     *
     * @return {@code true} when the preference listener is registered
     */
    public static synchronized boolean isWatchingPreferences()
    {
        return listener != null;
    }

    /**
     * Detaches the preference listener and stops the background scheduler. Called when the plug-in stops so
     * a dynamic update or uninstall leaves no stale callback or recurring timer bound to the old class
     * loader.
     */
    public static synchronized void shutdown()
    {
        if (listener != null)
        {
            InstanceScope.INSTANCE.getNode(SonarqPlugin.PLUGIN_ID).removePreferenceChangeListener(listener);
            listener = null;
        }
        AutoSyncScheduler.stop();
    }

    private static void onPreferenceChange(PreferenceChangeEvent event)
    {
        String key = event.getKey();
        if (PreferenceConstants.PREF_AUTO_SYNC.equals(key)
            || PreferenceConstants.PREF_AUTO_SYNC_MINUTES.equals(key))
        {
            AutoSyncScheduler.applyPreferences();
        }
        else if (PreferenceConstants.PREF_SHOW_MARKERS.equals(key) && isDisabled(event.getNewValue()))
        {
            clearMarkers();
        }
    }

    private static boolean isDisabled(Object newValue)
    {
        return "false".equals(newValue); //$NON-NLS-1$
    }

    private static void clearMarkers()
    {
        Job job = Job.create(Messages.MarkerSyncJob_Name, monitor ->
        {
            try
            {
                new IssueMarkerSynchronizer().clearAll();
            }
            catch (CoreException e)
            {
                Platform.getLog(SonarqStartup.class).warn(e.getMessage(), e);
            }
        });
        job.setSystem(true);
        job.schedule();
    }
}
