/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.core.analysis;

/**
 * User-facing configuration for launching an analysis run.
 *
 * @param mode how the analysis is launched, not {@code null}
 * @param scannerPath the path to a pre-installed scanner for {@link AnalysisLaunchMode#LOCAL_PATH},
 *     may be {@code null} or empty for other modes
 * @param ciUrl the pipeline URL for {@link AnalysisLaunchMode#CI_TRIGGER}, may be {@code null} or empty
 * @param extraArgs extra scanner arguments, may be {@code null} or empty
 */
public record AnalysisLaunchConfig(AnalysisLaunchMode mode, String scannerPath, String ciUrl, String extraArgs)
{
}
