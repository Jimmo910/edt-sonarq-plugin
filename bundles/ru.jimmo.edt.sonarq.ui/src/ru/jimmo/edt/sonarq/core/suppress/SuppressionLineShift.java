/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.core.suppress;

import java.util.ArrayList;
import java.util.List;

import ru.jimmo.edt.sonarq.core.model.IssueSnapshot;
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
     * Removes every issue the inserted comment pair silenced and shifts the line number of every other issue
     * in the same file to account for the two lines {@link BslSuppression#insert} added around the suppressed
     * issue's line: the {@code -off} comment above it, and the {@code -on} comment below it.
     *
     * <p>"Every issue silenced" is not only the one the user clicked: the comment pair disables the rule for
     * the whole wrapped line, so a second finding of the same rule on that line (routine for e.g.
     * {@code MissingSpace}, and since local-analysis keys carry the column they no longer collide into one
     * issue) is gone from the next analysis too and must not survive here either.
     *
     * @param issues the issues before the suppression, not {@code null}
     * @param suppressed the issue that was just suppressed - its {@link SonarIssue#componentKey()},
     *     {@link SonarIssue#ruleKey()} and {@link SonarIssue#line()} decide what is dropped and what is
     *     renumbered, not {@code null}
     * @return a new list: the suppressed issue and its same-rule/same-line siblings removed, the remaining
     *     same-file issues at or below the suppressed line renumbered, everything else unchanged
     */
    public static List<SonarIssue> applyAfterSuppress(List<SonarIssue> issues, SonarIssue suppressed)
    {
        return applyAfterSuppress(issues, suppressed, suppressed.line());
    }

    /**
     * The same, for a suppression that did not land on the line the issue was reported at.
     *
     * <p>The comment pair goes around the line the issue's {@link LineAnchor} named, which after a
     * server-mode refresh - or any edit above the issue - is regularly a few lines away from the reported
     * number (see {@link SuppressionResult#line()}). The file therefore grew two lines <em>there</em>, so
     * that is the line the renumbering has to pivot on: renumbering around the reported number instead moves
     * every issue between the two by two lines it never gained.
     *
     * <p>Which issues the comment pair <em>silenced</em> is a different question, and deliberately still
     * asked in the issue list's own numbering: the siblings that share the wrapped line are the ones this
     * list has at {@code suppressed.line()}, whatever the file calls that line.
     *
     * @param issues the issues before the suppression, not {@code null}
     * @param suppressed the issue that was just suppressed, not {@code null}
     * @param codeLine the 1-based line the {@code -off}/{@code -on} comments were really wrapped around, in
     *     the numbering the file had before the insertion
     * @return a new list, as {@link #applyAfterSuppress(List, SonarIssue)} describes
     */
    public static List<SonarIssue> applyAfterSuppress(List<SonarIssue> issues, SonarIssue suppressed,
        int codeLine)
    {
        List<SonarIssue> result = new ArrayList<>(issues.size());
        for (SonarIssue issue : issues)
        {
            if (isSilencedBy(issue, suppressed))
            {
                continue;
            }
            result.add(shiftIfSameFile(issue, suppressed.componentKey(), codeLine));
        }
        return result;
    }

    /**
     * Applies {@link #applyAfterSuppress(List, SonarIssue)} to a whole snapshot, keeping everything the
     * snapshot knows besides its issues.
     *
     * <p>The total matters as much as the issues. It is what tells the view that the result on screen is only
     * the first {@code N} of {@code M} findings the server (or a capped local analysis) actually has, and
     * rebuilding the snapshot with "total = issues I still hold" silently turned every truncated result into a
     * complete one at the first suppression - the "Showing first N of M" warning simply disappeared and never
     * came back until the next refresh. The suppressed findings are gone for good, so the total loses exactly
     * them and nothing else; it can never fall below the number of issues left, which would claim a truncation
     * that is not there.
     *
     * @param snapshot the snapshot before the suppression, not {@code null}
     * @param suppressed the issue that was just suppressed, not {@code null}
     * @return a snapshot with the same query, load time and truncation state, and the adjusted issues, never
     *     {@code null}
     */
    public static IssueSnapshot applyAfterSuppress(IssueSnapshot snapshot, SonarIssue suppressed)
    {
        return applyAfterSuppress(snapshot, suppressed, suppressed.line());
    }

    /**
     * Applies {@link #applyAfterSuppress(List, SonarIssue, int)} to a whole snapshot, keeping everything the
     * snapshot knows besides its issues (see {@link #applyAfterSuppress(IssueSnapshot, SonarIssue)} for why
     * the total is carried over rather than recomputed).
     *
     * @param snapshot the snapshot before the suppression, not {@code null}
     * @param suppressed the issue that was just suppressed, not {@code null}
     * @param codeLine the 1-based line the comments were really wrapped around
     * @return a snapshot with the same query, load time and truncation state, and the adjusted issues, never
     *     {@code null}
     */
    public static IssueSnapshot applyAfterSuppress(IssueSnapshot snapshot, SonarIssue suppressed, int codeLine)
    {
        List<SonarIssue> adjusted = applyAfterSuppress(snapshot.issues(), suppressed, codeLine);
        int silenced = snapshot.issues().size() - adjusted.size();
        int total = Math.max(adjusted.size(), snapshot.serverTotal() - silenced);
        return new IssueSnapshot(snapshot.query(), adjusted, total, snapshot.loadedAt());
    }

    /**
     * The per-line arithmetic of one suppression, shared by every model that keeps line numbers of its own:
     * the issue snapshot of the SonarQube Issues view (see {@link #applyAfterSuppress}) and the workspace
     * markers of the Problems view (see
     * {@code ru.jimmo.edt.sonarq.ui.suppress.SuppressMarkerResolution}), which is updated even when the view
     * is closed and therefore cannot rely on the snapshot path.
     *
     * @param line the 1-based line number recorded before the edit
     * @param codeLine the 1-based line the {@code -off}/{@code -on} comments were wrapped around
     * @return {@code line + 1} for the wrapped line itself (pushed down by the {@code -off} comment),
     *     {@code line + 2} for anything below it (pushed down by both comments), {@code line} unchanged for
     *     anything above it
     */
    public static int shiftedLine(int line, int codeLine)
    {
        if (line == codeLine)
        {
            return line + 1;
        }
        if (line > codeLine)
        {
            return line + 2;
        }
        return line;
    }

    /**
     * Tells whether {@code issue} is silenced by the comment pair written for {@code suppressed}: it is
     * either that very issue, or another finding of the same rule on the same line of the same file, which
     * the wrapped {@code -off}/{@code -on} pair disables just as well.
     *
     * @param issue the issue to test, not {@code null}
     * @param suppressed the issue the comments were written for, not {@code null}
     * @return {@code true} when {@code issue} must be dropped from the model
     */
    private static boolean isSilencedBy(SonarIssue issue, SonarIssue suppressed)
    {
        if (issue.key().equals(suppressed.key()))
        {
            return true;
        }
        return issue.line() == suppressed.line() && issue.componentKey().equals(suppressed.componentKey())
            && BslSuppression.bareRuleKey(issue.ruleKey())
                .equals(BslSuppression.bareRuleKey(suppressed.ruleKey()));
    }

    private static SonarIssue shiftIfSameFile(SonarIssue issue, String suppressedComponentKey, int codeLine)
    {
        if (!issue.componentKey().equals(suppressedComponentKey))
        {
            return issue;
        }
        int shifted = shiftedLine(issue.line(), codeLine);
        // withLine keeps the issue's line anchor: the comment pair moved the line, it did not change its
        // text, and that anchor is what lets the next suppression find the line again even if this
        // renumbering is later undone by a refresh that restores the analysis-time numbers.
        return shifted == issue.line() ? issue : issue.withLine(shifted);
    }
}
