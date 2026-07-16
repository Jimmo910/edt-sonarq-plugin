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
 * @param issues the issues found by the analysis, not {@code null}
 * @param rules the rule descriptions reported by the tool driver, keyed by rule id, not {@code null}
 */
public record SarifReport(List<SonarIssue> issues, Map<String, SonarRule> rules)
{
}
