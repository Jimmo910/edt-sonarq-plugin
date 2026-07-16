/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.ui.sync;

import java.util.Optional;

import org.eclipse.core.resources.IProject;

import ru.jimmo.edt.sonarq.core.client.SonarConnection;
import ru.jimmo.edt.sonarq.core.client.SonarHttpClient;
import ru.jimmo.edt.sonarq.core.provider.ServerIssueProvider;
import ru.jimmo.edt.sonarq.core.settings.ProjectBinding;
import ru.jimmo.edt.sonarq.ui.settings.ProjectBindingStore;
import ru.jimmo.edt.sonarq.ui.settings.SonarConnectionFactory;

/**
 * Resolves the {@link ProjectRefreshInputs} of a workspace project from its binding and the workspace
 * connection settings.
 */
public final class RefreshInputsFactory
{
    private RefreshInputsFactory()
    {
    }

    /**
     * Resolves the refresh inputs of a project.
     *
     * @param project the workspace project, may be {@code null}
     * @return the inputs, or empty when the project is {@code null}, not open, has no configured binding,
     *     or the server connection is not configured
     */
    public static Optional<ProjectRefreshInputs> create(IProject project)
    {
        if (project == null || !project.isOpen())
        {
            return Optional.empty();
        }
        ProjectBinding binding = new ProjectBindingStore().load(project);
        if (!binding.isConfigured())
        {
            return Optional.empty();
        }
        Optional<SonarConnection> connection = new SonarConnectionFactory().create();
        if (connection.isEmpty())
        {
            return Optional.empty();
        }
        SonarConnection resolved = connection.get();
        return Optional.of(new ProjectRefreshInputs(project, binding, resolved,
            new ServerIssueProvider(new SonarHttpClient(resolved))));
    }
}
