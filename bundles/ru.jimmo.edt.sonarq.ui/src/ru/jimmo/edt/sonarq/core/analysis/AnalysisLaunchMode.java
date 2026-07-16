/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.core.analysis;

import java.util.Locale;

/** How an analysis run is launched for a project. */
public enum AnalysisLaunchMode
{
    /** Download and run the SonarScanner CLI managed by the plugin. */
    LOCAL_AUTO,
    /** Run a SonarScanner CLI already installed at a user-provided path. */
    LOCAL_PATH,
    /** Trigger a remote continuous-integration pipeline. */
    CI_TRIGGER;

    /**
     * Resolves a mode from its persisted key.
     *
     * @param key the stored key, may be {@code null}
     * @return the matching mode, or {@link #LOCAL_AUTO} when the key is {@code null} or unknown
     */
    public static AnalysisLaunchMode fromKey(String key)
    {
        if (key == null)
        {
            return LOCAL_AUTO;
        }
        try
        {
            return valueOf(key.trim().toUpperCase(Locale.ROOT));
        }
        catch (IllegalArgumentException e)
        {
            return LOCAL_AUTO;
        }
    }
}
