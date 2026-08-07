/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.ui.views;

import java.nio.file.Path;

import org.eclipse.core.resources.IProject;

import ru.jimmo.edt.sonarq.core.analysis.AnalysisLaunchConfig;
import ru.jimmo.edt.sonarq.core.analysis.SecretScrubber;
import ru.jimmo.edt.sonarq.core.client.ISonarServerClient;
import ru.jimmo.edt.sonarq.core.client.SonarConnection;
import ru.jimmo.edt.sonarq.core.settings.ProjectBinding;

/**
 * Immutable bundle of inputs for a single {@link AnalysisJob} run.
 *
 * <p>{@link #toString()} redacts the CI secret (and inherits the token redaction of
 * {@link SonarConnection#toString()}): the default record {@code toString} would print both credentials
 * verbatim into any log line or status message that ever interpolates the request, and the job already
 * funnels arbitrary failures into {@code String.valueOf} (review minor M3).
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
    /**
     * Returns a debug description of these inputs with every credential redacted: the CI secret directly,
     * the server token through {@link SonarConnection#toString()}, and the trigger token that CI providers
     * such as GitLab expect inside the configured CI URL through {@link SecretScrubber}. The rest - project,
     * binding, launch mode, branch and state location - stays visible, since that is what a failing run has
     * to be diagnosed from.
     *
     * @return the description, never {@code null}
     */
    @Override
    public String toString()
    {
        return "AnalysisRequest[project=" + project.getName() //$NON-NLS-1$
            + ", binding=" + binding //$NON-NLS-1$
            + ", connection=" + connection //$NON-NLS-1$
            + ", config=" + SecretScrubber.scrub(String.valueOf(config), ciSecret) //$NON-NLS-1$
            + ", requestedBranch=" + requestedBranch //$NON-NLS-1$
            + ", branchesSupported=" + branchesSupported //$NON-NLS-1$
            + ", ciSecret=" + describeCiSecret() //$NON-NLS-1$
            + ", stateLocation=" + stateLocation + ']'; //$NON-NLS-1$
    }

    private String describeCiSecret()
    {
        return ciSecret == null || ciSecret.isEmpty() ? "<none>" : "***"; //$NON-NLS-1$ //$NON-NLS-2$
    }
}
