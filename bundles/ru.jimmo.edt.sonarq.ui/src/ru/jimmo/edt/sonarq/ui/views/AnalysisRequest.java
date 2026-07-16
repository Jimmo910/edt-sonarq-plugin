/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.ui.views;

import java.nio.file.Path;

import org.eclipse.core.resources.IProject;

import ru.jimmo.edt.sonarq.core.analysis.AnalysisLaunchConfig;
import ru.jimmo.edt.sonarq.core.client.ISonarServerClient;
import ru.jimmo.edt.sonarq.core.client.SonarConnection;
import ru.jimmo.edt.sonarq.core.settings.ProjectBinding;

/**
 * Immutable bundle of inputs for a single {@link AnalysisJob} run.
 *
 * @param project the workspace project to analyze, not {@code null} with a local location
 * @param binding the SonarQube binding of the project, must be configured
 * @param connection the server connection (host URL, token, timeout), not {@code null}
 * @param config the analysis launch configuration (mode, scanner path, CI URL, extra args), not {@code null}
 * @param requestedBranch the branch to analyze, or {@code null} to let the scanner default it
 * @param branchesSupported whether the server edition supports branches (from the last refresh)
 * @param ciSecret the {@code Authorization} header value for the CI trigger, may be empty
 * @param stateLocation the plug-in state directory used for the scanner install and work dirs, not {@code null}
 * @param client the server client used for language and Compute Engine task queries, not {@code null}
 */
public record AnalysisRequest(IProject project, ProjectBinding binding, SonarConnection connection,
    AnalysisLaunchConfig config, String requestedBranch, boolean branchesSupported, String ciSecret,
    Path stateLocation, ISonarServerClient client)
{
}
