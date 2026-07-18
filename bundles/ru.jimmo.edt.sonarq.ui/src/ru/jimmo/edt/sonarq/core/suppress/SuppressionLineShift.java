/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.core.suppress;

import java.util.ArrayList;
import java.util.List;

import ru.jimmo.edt.sonarq.core.model.SonarIssue;

/**
 * Adjusts an in-memory list of {@link SonarIssue}s right after a quick-suppress edit (issue #7), so the issue
 * tree and Problems-view markers reflect the {@code // BSLLS:<rule>-off}/{@code -on} comment pair
 * {@link BslSuppression#insert} just wrote, without waiting for the next asynchronous refresh (or a full
 * re-analysis, in local analysis mode) to catch up.
 *
 * <p>Without this, a second suppression in the same file - before that refresh finishes - would read a stale
 * line number for the next issue and corrupt the file (see {@link BslSuppression}'s target-line guard).
 */
public final class SuppressionLineShift
{
    private SuppressionLineShift()
    {
    }

    /**
     * Removes the just-suppressed issue and shifts the line number of every other issue in the same file to
     * account for the two lines {@link BslSuppression#insert} added around {@code codeLine}: the {@code -off}
     * comment above it, and the {@code -on} comment below it.
     *
     * @param issues the issues before the suppression, not {@code null}
     * @param suppressedIssueKey the {@link SonarIssue#key()} of the issue that was just suppressed, not
     *     {@code null}
     * @param suppressedComponentKey the {@link SonarIssue#componentKey()} of the suppressed issue - only
     *     issues sharing this component key have their line shifted, not {@code null}
     * @param codeLine the 1-based line the suppression comments were wrapped around (the suppressed issue's
     *     own line before the edit)
     * @return a new list: the suppressed issue removed, same-file issues at or below {@code codeLine}
     *     renumbered, everything else unchanged
     */
    public static List<SonarIssue> applyAfterSuppress(List<SonarIssue> issues, String suppressedIssueKey,
        String suppressedComponentKey, int codeLine)
    {
        List<SonarIssue> result = new ArrayList<>(issues.size());
        for (SonarIssue issue : issues)
        {
            if (issue.key().equals(suppressedIssueKey))
            {
                continue;
            }
            result.add(shiftIfSameFile(issue, suppressedComponentKey, codeLine));
        }
        return result;
    }

    private static SonarIssue shiftIfSameFile(SonarIssue issue, String suppressedComponentKey, int codeLine)
    {
        if (!issue.componentKey().equals(suppressedComponentKey))
        {
            return issue;
        }
        int line = issue.line();
        if (line == codeLine)
        {
            return withLine(issue, line + 1);
        }
        if (line > codeLine)
        {
            return withLine(issue, line + 2);
        }
        return issue;
    }

    private static SonarIssue withLine(SonarIssue issue, int newLine)
    {
        return new SonarIssue(issue.key(), issue.ruleKey(), issue.severity(), issue.type(), issue.componentKey(),
            issue.message(), newLine);
    }
}
