/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.ui.sync;

import org.eclipse.core.resources.IProject;

import ru.jimmo.edt.sonarq.core.client.SonarConnection;
import ru.jimmo.edt.sonarq.core.provider.IIssueProvider;
import ru.jimmo.edt.sonarq.core.settings.ProjectBinding;

/**
 * The resolved inputs needed to refresh SonarQube issues for one workspace project.
 *
 * @param project the workspace project to refresh, not {@code null}
 * @param binding the project's SonarQube binding, configured
 * @param connection the resolved server connection, not {@code null}
 * @param provider the issue provider built on top of {@code connection}, not {@code null}
 */
public record ProjectRefreshInputs(IProject project, ProjectBinding binding, SonarConnection connection,
    IIssueProvider provider)
{
}
