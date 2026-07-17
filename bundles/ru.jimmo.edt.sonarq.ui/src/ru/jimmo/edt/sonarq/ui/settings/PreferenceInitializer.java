/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.ui.settings;

import org.eclipse.core.runtime.preferences.AbstractPreferenceInitializer;
import org.eclipse.core.runtime.preferences.DefaultScope;
import org.eclipse.core.runtime.preferences.IEclipsePreferences;

import ru.jimmo.edt.sonarq.core.analysis.AnalysisLaunchMode;
import ru.jimmo.edt.sonarq.ui.SonarqPlugin;

/** Initializes default preference values. */
public class PreferenceInitializer extends AbstractPreferenceInitializer
{
    @Override
    public void initializeDefaultPreferences()
    {
        IEclipsePreferences node = DefaultScope.INSTANCE.getNode(SonarqPlugin.PLUGIN_ID);
        node.put(PreferenceConstants.PREF_SERVER_URL, ""); //$NON-NLS-1$
        node.putInt(PreferenceConstants.PREF_TIMEOUT_SECONDS, PreferenceConstants.DEFAULT_TIMEOUT_SECONDS);
        node.put(PreferenceConstants.PREF_MODE, PreferenceConstants.MODE_SERVER);
        node.put(PreferenceConstants.PREF_BSL_LS_PATH, ""); //$NON-NLS-1$
        node.put(PreferenceConstants.PREF_LAUNCH_MODE, AnalysisLaunchMode.LOCAL_AUTO.name());
        node.put(PreferenceConstants.PREF_SCANNER_PATH, ""); //$NON-NLS-1$
        node.put(PreferenceConstants.PREF_CI_URL, ""); //$NON-NLS-1$
        node.put(PreferenceConstants.PREF_EXTRA_ARGS, ""); //$NON-NLS-1$
        node.putBoolean(PreferenceConstants.PREF_SHOW_MARKERS, true);
        node.putBoolean(PreferenceConstants.PREF_AUTO_SYNC, false);
        node.putInt(PreferenceConstants.PREF_AUTO_SYNC_MINUTES, PreferenceConstants.DEFAULT_AUTO_SYNC_MINUTES);
        node.put(PreferenceConstants.PREF_DISABLED_BSL_DIAGNOSTICS, ""); //$NON-NLS-1$
    }
}
