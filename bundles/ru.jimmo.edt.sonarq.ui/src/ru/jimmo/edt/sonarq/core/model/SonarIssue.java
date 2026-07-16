/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.core.model;

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
 */
public record SonarIssue(String key, String ruleKey, SonarSeverity severity, SonarIssueType type,
    String componentKey, String message, int line)
{
}
