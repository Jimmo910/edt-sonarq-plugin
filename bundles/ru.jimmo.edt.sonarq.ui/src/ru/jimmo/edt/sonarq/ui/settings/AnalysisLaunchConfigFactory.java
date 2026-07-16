/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.ui.settings;

import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.preferences.IPreferencesService;

import ru.jimmo.edt.sonarq.core.analysis.AnalysisLaunchConfig;
import ru.jimmo.edt.sonarq.core.analysis.AnalysisLaunchMode;
import ru.jimmo.edt.sonarq.ui.SonarqPlugin;

/** Builds {@link AnalysisLaunchConfig} from the current workspace preferences. */
public final class AnalysisLaunchConfigFactory
{
    /**
     * Creates the analysis launch configuration from the current workspace settings.
     *
     * @return the configuration, never {@code null}
     */
    public AnalysisLaunchConfig create()
    {
        IPreferencesService service = Platform.getPreferencesService();
        AnalysisLaunchMode mode = AnalysisLaunchMode.fromKey(service.getString(SonarqPlugin.PLUGIN_ID,
            PreferenceConstants.PREF_LAUNCH_MODE, AnalysisLaunchMode.LOCAL_AUTO.name(), null));
        String scannerPath = service.getString(SonarqPlugin.PLUGIN_ID,
            PreferenceConstants.PREF_SCANNER_PATH, "", null); //$NON-NLS-1$
        String ciUrl = service.getString(SonarqPlugin.PLUGIN_ID,
            PreferenceConstants.PREF_CI_URL, "", null); //$NON-NLS-1$
        String extraArgs = service.getString(SonarqPlugin.PLUGIN_ID,
            PreferenceConstants.PREF_EXTRA_ARGS, "", null); //$NON-NLS-1$
        return new AnalysisLaunchConfig(mode, scannerPath, ciUrl, extraArgs);
    }
}
