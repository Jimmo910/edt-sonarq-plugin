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
 */
public final class SonarqStartup implements IStartup
{
    private static IPreferenceChangeListener listener;

    @Override
    public void earlyStartup()
    {
        AutoSyncScheduler.applyPreferences();
        listener = SonarqStartup::onPreferenceChange;
        InstanceScope.INSTANCE.getNode(SonarqPlugin.PLUGIN_ID).addPreferenceChangeListener(listener);
    }

    /**
     * Detaches the preference listener and stops the background scheduler. Called when the plug-in stops so
     * a dynamic update or uninstall leaves no stale callback or recurring timer bound to the old class
     * loader.
     */
    public static void shutdown()
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
