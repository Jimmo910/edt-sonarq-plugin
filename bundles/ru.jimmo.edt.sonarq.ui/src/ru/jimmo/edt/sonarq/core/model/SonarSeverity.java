/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.core.model;

/** Issue severity as reported by SonarQube. */
public enum SonarSeverity
{
    BLOCKER,
    CRITICAL,
    MAJOR,
    MINOR,
    INFO;

    /**
     * Parses a severity from its JSON representation.
     *
     * @param value the JSON value, may be {@code null}
     * @return the parsed severity, {@code INFO} when unknown
     */
    public static SonarSeverity fromJson(String value)
    {
        if (value == null)
        {
            return INFO;
        }
        try
        {
            return valueOf(value);
        }
        catch (IllegalArgumentException e)
        {
            return INFO;
        }
    }
}
