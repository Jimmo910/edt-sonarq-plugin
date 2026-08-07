/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.core.localanalysis;

import java.util.List;
import java.util.Map;

import ru.jimmo.edt.sonarq.core.model.SonarIssue;
import ru.jimmo.edt.sonarq.core.model.SonarRule;

/**
 * The result of parsing a SARIF report produced by a local BSL Language Server analysis run.
 *
 * @param issues the issues found by the analysis and kept within the parser's issue limit, not {@code null}
 * @param rules the rule descriptions reported by the tool driver, keyed by rule id, not {@code null}
 * @param totalResults the number of results the report contained, counting the ones the issue limit
 *     dropped; never less than {@code issues.size()}
 */
public record SarifReport(List<SonarIssue> issues, Map<String, SonarRule> rules, int totalResults)
{
    /**
     * Creates a report of an unlimited parse, where every result became an issue.
     *
     * @param issues the issues found by the analysis, not {@code null}
     * @param rules the rule descriptions reported by the tool driver, keyed by rule id, not {@code null}
     */
    public SarifReport(List<SonarIssue> issues, Map<String, SonarRule> rules)
    {
        this(issues, rules, issues.size());
    }

    /**
     * Tells whether the parser's issue limit dropped part of the report.
     *
     * @return {@code true} when the report held more results than {@link #issues()} carries
     */
    public boolean truncated()
    {
        return totalResults > issues.size();
    }
}
