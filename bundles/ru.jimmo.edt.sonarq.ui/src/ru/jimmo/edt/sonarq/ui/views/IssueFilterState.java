/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.ui.views;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

import ru.jimmo.edt.sonarq.core.model.SonarIssue;
import ru.jimmo.edt.sonarq.core.model.SonarIssueType;
import ru.jimmo.edt.sonarq.core.model.SonarSeverity;

/**
 * Mutable filter state for the issues view: enabled severities, enabled issue types and a free-text filter.
 * By default every severity and type is enabled and the text filter is empty, so {@link #matches(SonarIssue)}
 * accepts every issue.
 */
public final class IssueFilterState
{
    private final Set<SonarSeverity> enabledSeverities = EnumSet.allOf(SonarSeverity.class);
    private final Set<SonarIssueType> enabledTypes = EnumSet.allOf(SonarIssueType.class);
    private String text = ""; //$NON-NLS-1$

    /**
     * Tests whether an issue passes the current severity, type and text filters.
     *
     * @param issue the issue to test, not {@code null}
     * @return {@code true} if the issue's severity and type are enabled and, when a text filter is set,
     *     its rule key or message contains the filter text (case-insensitive)
     */
    public boolean matches(SonarIssue issue)
    {
        if (!enabledSeverities.contains(issue.severity()))
        {
            return false;
        }
        if (!enabledTypes.contains(issue.type()))
        {
            return false;
        }
        if (text.isEmpty())
        {
            return true;
        }
        String needle = text.toLowerCase(Locale.ROOT);
        return issue.message().toLowerCase(Locale.ROOT).contains(needle)
            || issue.ruleKey().toLowerCase(Locale.ROOT).contains(needle);
    }

    /**
     * Toggles whether the given severity is included by {@link #matches(SonarIssue)}.
     *
     * @param severity the severity to toggle, not {@code null}
     */
    public void toggleSeverity(SonarSeverity severity)
    {
        if (!enabledSeverities.remove(severity))
        {
            enabledSeverities.add(severity);
        }
    }

    /**
     * Tests whether the given severity is currently enabled.
     *
     * @param severity the severity to test, not {@code null}
     * @return {@code true} if issues of this severity currently pass the filter
     */
    public boolean isSeverityEnabled(SonarSeverity severity)
    {
        return enabledSeverities.contains(severity);
    }

    /**
     * Toggles whether the given issue type is included by {@link #matches(SonarIssue)}.
     *
     * @param type the issue type to toggle, not {@code null}
     */
    public void toggleType(SonarIssueType type)
    {
        if (!enabledTypes.remove(type))
        {
            enabledTypes.add(type);
        }
    }

    /**
     * Tests whether the given issue type is currently enabled.
     *
     * @param type the issue type to test, not {@code null}
     * @return {@code true} if issues of this type currently pass the filter
     */
    public boolean isTypeEnabled(SonarIssueType type)
    {
        return enabledTypes.contains(type);
    }

    /**
     * Sets the free-text filter applied to the rule key and the message.
     *
     * @param newText the filter text, {@code null} is treated as an empty filter
     */
    public void setText(String newText)
    {
        text = newText == null ? "" : newText; //$NON-NLS-1$
    }
}
