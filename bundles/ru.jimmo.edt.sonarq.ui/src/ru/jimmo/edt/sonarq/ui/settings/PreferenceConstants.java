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

    /** The default request timeout, in seconds. */
    public static final int DEFAULT_TIMEOUT_SECONDS = 30;

    private PreferenceConstants()
    {
    }
}
