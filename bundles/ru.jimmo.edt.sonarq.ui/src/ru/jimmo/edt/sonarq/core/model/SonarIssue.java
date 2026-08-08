/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.core.model;

import ru.jimmo.edt.sonarq.core.suppress.LineAnchor;

/**
 * A single unresolved issue from the SonarQube issue search.
 *
 * @param key the server-side issue key, not {@code null}
 * @param ruleKey the rule key, e.g. {@code bsl:MethodSize}, not {@code null}
 * @param severity the severity, not {@code null}
 * @param type the issue type, not {@code null}
 * @param componentKey the component key ({@code <projectKey>:<path>}), not {@code null}
 * @param message the issue message, not {@code null}
 * @param line the 1-based line number, {@code 0} for file-level issues
 * @param lineAnchor the fingerprint of the source line this issue was reported on (see {@link LineAnchor}),
 *     or {@link LineAnchor#NONE} when it could not be computed - the issue is then unverifiable, and a
 *     quick-suppress refuses it rather than editing {@code line} unchecked. Issue producers (the server JSON
 *     and SARIF parsers)
 *     do not have the file at hand and leave it empty; it is filled in where issues are mapped to workspace
 *     files (see {@code ru.jimmo.edt.sonarq.ui.resources.IssueAnchors}). Never {@code null}: the canonical
 *     constructor normalizes {@code null} to {@link LineAnchor#NONE}
 */
public record SonarIssue(String key, String ruleKey, SonarSeverity severity, SonarIssueType type,
    String componentKey, String message, int line, String lineAnchor)
{
    /**
     * Normalizes a {@code null} {@code lineAnchor} to {@link LineAnchor#NONE}, so no consumer has to
     * null-check the one component that arrives empty from every issue producer.
     */
    public SonarIssue
    {
        lineAnchor = lineAnchor != null ? lineAnchor : LineAnchor.NONE;
    }

    /**
     * Creates an issue that carries no line anchor yet.
     *
     * <p>The form every issue producer uses: a parser reading a server response or a SARIF report has no
     * access to the workspace file, so it cannot fingerprint the line. Keeping it as a constructor of its own
     * also keeps the anchor from silently defaulting at call sites that <em>could</em> supply one.
     *
     * @param key the server-side issue key, not {@code null}
     * @param ruleKey the rule key, not {@code null}
     * @param severity the severity, not {@code null}
     * @param type the issue type, not {@code null}
     * @param componentKey the component key, not {@code null}
     * @param message the issue message, not {@code null}
     * @param line the 1-based line number, {@code 0} for file-level issues
     */
    public SonarIssue(String key, String ruleKey, SonarSeverity severity, SonarIssueType type,
        String componentKey, String message, int line)
    {
        this(key, ruleKey, severity, type, componentKey, message, line, LineAnchor.NONE);
    }

    /**
     * Returns a copy of this issue carrying the given line anchor.
     *
     * @param anchor the anchor to record, not {@code null}
     * @return a copy with {@link #lineAnchor()} replaced, never {@code null}
     */
    public SonarIssue withAnchor(String anchor)
    {
        return new SonarIssue(key, ruleKey, severity, type, componentKey, message, line, anchor);
    }

    /**
     * Returns a copy of this issue at a different line, keeping its anchor.
     *
     * <p>The anchor deliberately survives renumbering: it describes the <em>content</em> of the line, which a
     * suppression above it moves but does not change, and that is exactly what lets the next suppression
     * find the line again if the number turns out to be stale (see
     * {@code ru.jimmo.edt.sonarq.core.suppress.SuppressionLineShift}).
     *
     * @param newLine the 1-based line number to record
     * @return a copy with {@link #line()} replaced, never {@code null}
     */
    public SonarIssue withLine(int newLine)
    {
        return new SonarIssue(key, ruleKey, severity, type, componentKey, message, newLine, lineAnchor);
    }
}
