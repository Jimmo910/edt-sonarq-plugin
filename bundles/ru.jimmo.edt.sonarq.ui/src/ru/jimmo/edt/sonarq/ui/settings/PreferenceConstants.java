/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.ui.settings;

import ru.jimmo.edt.sonarq.core.localanalysis.BslUpdateChannel;

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

    /**
     * The BSL Language Server maximum JVM heap, in gigabytes, preference key, used only in
     * {@link #MODE_LOCAL}. The bundled jpackage app-image pins {@code -Xmx4g} in its own launcher
     * configuration file, which is too little for large 1C configurations and fails analysis with an
     * {@link OutOfMemoryError}; this preference is rewritten into that file before every local analysis
     * run (see {@code BslServerInstaller#configureHeap}).
     */
    public static final String PREF_BSL_LS_MAX_HEAP_GB = "bslLsMaxHeapGb"; //$NON-NLS-1$

    /** The default BSL Language Server maximum JVM heap, in gigabytes. */
    public static final int DEFAULT_BSL_LS_MAX_HEAP_GB = 4;

    /**
     * The BSL Language Server engine update-channel preference key, used only in {@link #MODE_LOCAL}. Stored
     * as one of {@link #UPDATE_CHANNEL_FIXED}, {@link #UPDATE_CHANNEL_STABLE} or
     * {@link #UPDATE_CHANNEL_PRERELEASE}; see {@link #channelFromPreference(String)}.
     */
    public static final String PREF_BSL_LS_UPDATE_CHANNEL = "bslLsUpdateChannel"; //$NON-NLS-1$

    /** The {@link #PREF_BSL_LS_UPDATE_CHANNEL} value mapping to {@link BslUpdateChannel#FIXED}. */
    public static final String UPDATE_CHANNEL_FIXED = "fixed"; //$NON-NLS-1$

    /** The {@link #PREF_BSL_LS_UPDATE_CHANNEL} value mapping to {@link BslUpdateChannel#STABLE}. */
    public static final String UPDATE_CHANNEL_STABLE = "stable"; //$NON-NLS-1$

    /** The {@link #PREF_BSL_LS_UPDATE_CHANNEL} value mapping to {@link BslUpdateChannel#PRERELEASE}. */
    public static final String UPDATE_CHANNEL_PRERELEASE = "prerelease"; //$NON-NLS-1$

    /** The default {@link #PREF_BSL_LS_UPDATE_CHANNEL} value. */
    public static final String DEFAULT_BSL_LS_UPDATE_CHANNEL = UPDATE_CHANNEL_STABLE;

    private PreferenceConstants()
    {
    }

    /**
     * Maps a stored {@link #PREF_BSL_LS_UPDATE_CHANNEL} value to the corresponding {@link BslUpdateChannel}.
     * An unknown or blank value (including {@code null}) defaults to {@link BslUpdateChannel#STABLE}, the
     * same as {@link #DEFAULT_BSL_LS_UPDATE_CHANNEL}.
     *
     * @param stored the stored preference value, may be {@code null} or blank
     * @return the mapped channel, never {@code null}
     */
    public static BslUpdateChannel channelFromPreference(String stored)
    {
        if (UPDATE_CHANNEL_FIXED.equals(stored))
        {
            return BslUpdateChannel.FIXED;
        }
        if (UPDATE_CHANNEL_PRERELEASE.equals(stored))
        {
            return BslUpdateChannel.PRERELEASE;
        }
        return BslUpdateChannel.STABLE;
    }
}
