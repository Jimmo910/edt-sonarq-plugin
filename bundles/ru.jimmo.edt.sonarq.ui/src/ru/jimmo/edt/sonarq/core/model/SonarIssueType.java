/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.core.model;

/** Issue type as reported by SonarQube. */
public enum SonarIssueType
{
    BUG,
    VULNERABILITY,
    CODE_SMELL,
    UNKNOWN;

    /**
     * Parses an issue type from its JSON representation.
     *
     * @param value the JSON value, may be {@code null}
     * @return the parsed type, {@code UNKNOWN} when not recognized
     */
    public static SonarIssueType fromJson(String value)
    {
        if (value == null)
        {
            return UNKNOWN;
        }
        try
        {
            return valueOf(value);
        }
        catch (IllegalArgumentException e)
        {
            return UNKNOWN;
        }
    }
}
