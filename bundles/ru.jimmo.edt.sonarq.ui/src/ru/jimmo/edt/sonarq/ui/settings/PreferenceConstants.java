/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.ui.settings;

/** Keys and defaults for the plug-in's workspace preferences. */
public final class PreferenceConstants
{
    /** The SonarQube server URL preference key. */
    public static final String PREF_SERVER_URL = "serverUrl"; //$NON-NLS-1$

    /** The HTTP request timeout, in seconds, preference key. */
    public static final String PREF_TIMEOUT_SECONDS = "timeoutSeconds"; //$NON-NLS-1$

    /** The connection mode preference key. */
    public static final String PREF_MODE = "mode"; //$NON-NLS-1$

    /** The {@link #PREF_MODE} value for a plain SonarQube/SonarCloud server. */
    public static final String MODE_SERVER = "server"; //$NON-NLS-1$

    /** The {@link #PREF_MODE} value for local analysis with the BSL Language Server. */
    public static final String MODE_LOCAL = "local"; //$NON-NLS-1$

    /** The BSL Language Server executable path preference key, used only in {@link #MODE_LOCAL}. */
    public static final String PREF_BSL_LS_PATH = "bslLsPath"; //$NON-NLS-1$

    /** The default request timeout, in seconds. */
    public static final int DEFAULT_TIMEOUT_SECONDS = 30;

    /** The analysis launch mode preference key. */
    public static final String PREF_LAUNCH_MODE = "launchMode"; //$NON-NLS-1$

    /** The local scanner path preference key. */
    public static final String PREF_SCANNER_PATH = "scannerPath"; //$NON-NLS-1$

    /** The CI trigger URL preference key. */
    public static final String PREF_CI_URL = "ciUrl"; //$NON-NLS-1$

    /** The extra scanner arguments preference key. */
    public static final String PREF_EXTRA_ARGS = "scannerExtraArgs"; //$NON-NLS-1$

    /** The preference key controlling whether SonarQube issues are shown as editor markers. */
    public static final String PREF_SHOW_MARKERS = "showMarkers"; //$NON-NLS-1$

    /** The preference key controlling whether issues are refreshed automatically in the background. */
    public static final String PREF_AUTO_SYNC = "autoSync"; //$NON-NLS-1$

    /** The automatic background refresh interval, in minutes, preference key. */
    public static final String PREF_AUTO_SYNC_MINUTES = "autoSyncMinutes"; //$NON-NLS-1$

    /** The default automatic background refresh interval, in minutes. */
    public static final int DEFAULT_AUTO_SYNC_MINUTES = 15;

    /**
     * The preference key holding the comma-separated list of disabled BSL diagnostic keys, used only in
     * {@link #MODE_LOCAL} to generate the BSL Language Server checks configuration. Empty (the default)
     * means every diagnostic is enabled.
     */
    public static final String PREF_DISABLED_BSL_DIAGNOSTICS = "disabledBslDiagnostics"; //$NON-NLS-1$

    private PreferenceConstants()
    {
    }
}
