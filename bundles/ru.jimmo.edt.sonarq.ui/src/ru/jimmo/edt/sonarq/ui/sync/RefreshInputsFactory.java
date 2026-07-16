/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.ui.sync;

import java.nio.file.Path;
import java.util.Optional;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.preferences.IPreferencesService;

import ru.jimmo.edt.sonarq.core.client.SonarConnection;
import ru.jimmo.edt.sonarq.core.client.SonarHttpClient;
import ru.jimmo.edt.sonarq.core.localanalysis.LocalIssueProvider;
import ru.jimmo.edt.sonarq.core.localanalysis.ProcessAnalyzeRunner;
import ru.jimmo.edt.sonarq.core.provider.ServerIssueProvider;
import ru.jimmo.edt.sonarq.core.settings.ProjectBinding;
import ru.jimmo.edt.sonarq.ui.SonarqPlugin;
import ru.jimmo.edt.sonarq.ui.settings.PreferenceConstants;
import ru.jimmo.edt.sonarq.ui.settings.ProjectBindingStore;
import ru.jimmo.edt.sonarq.ui.settings.SonarConnectionFactory;

/**
 * Resolves the {@link ProjectRefreshInputs} of a workspace project from its binding and the workspace
 * connection settings.
 *
 * <p>The resolution depends on {@link PreferenceConstants#PREF_MODE}: in {@link PreferenceConstants#MODE_SERVER}
 * a server connection is required and a {@link ServerIssueProvider} is built; in
 * {@link PreferenceConstants#MODE_LOCAL} no server is contacted, the {@code connection} component is
 * {@code null} and a {@link LocalIssueProvider} runs the BSL Language Server against the project sources.
 */
public final class RefreshInputsFactory
{
    private RefreshInputsFactory()
    {
    }

    /**
     * Resolves the refresh inputs of a project.
     *
     * <p>In server mode the inputs are empty unless the project is open, has a configured binding and the
     * server connection is configured. In local mode only an open project with a resolvable location is
     * required — no binding and no server URL are needed.
     *
     * @param project the workspace project, may be {@code null}
     * @return the inputs, or empty when they cannot be resolved for the active mode
     */
    public static Optional<ProjectRefreshInputs> create(IProject project)
    {
        if (project == null || !project.isOpen())
        {
            return Optional.empty();
        }
        ProjectBinding binding = new ProjectBindingStore().load(project);
        IPreferencesService service = Platform.getPreferencesService();
        String mode = service.getString(SonarqPlugin.PLUGIN_ID, PreferenceConstants.PREF_MODE,
            PreferenceConstants.MODE_SERVER, null);
        if (PreferenceConstants.MODE_LOCAL.equals(mode))
        {
            return createLocal(project, binding, service);
        }
        return createServer(project, binding);
    }

    /**
     * Resolves server-mode inputs: current behavior, requiring a configured binding and connection.
     *
     * @param project the open workspace project, not {@code null}
     * @param binding the project's binding, not {@code null}
     * @return the inputs, or empty when the binding or the connection is not configured
     */
    private static Optional<ProjectRefreshInputs> createServer(IProject project, ProjectBinding binding)
    {
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
            new ServerIssueProvider(new SonarHttpClient(resolved)), binding.projectKey(), binding.pathPrefix()));
    }

    /**
     * Resolves local-analysis inputs: a {@link LocalIssueProvider} over the project sources with a
     * {@code null} connection. The project key defaults to the project name when the binding has none.
     *
     * @param project the open workspace project, not {@code null}
     * @param binding the project's binding, not {@code null}, may be unconfigured
     * @param service the preferences service used to read the executable override, not {@code null}
     * @return the inputs, or empty when the project has no resolvable location on disk
     */
    private static Optional<ProjectRefreshInputs> createLocal(IProject project, ProjectBinding binding,
        IPreferencesService service)
    {
        IPath location = project.getLocation();
        if (location == null)
        {
            return Optional.empty();
        }
        Path projectRoot = Path.of(location.toOSString());
        String projectKey = binding.projectKey().isEmpty() ? project.getName() : binding.projectKey();
        Path stateDir = Path.of(SonarqPlugin.getInstance().getStateLocation().toOSString());
        String overridePath = service.getString(SonarqPlugin.PLUGIN_ID, PreferenceConstants.PREF_BSL_LS_PATH,
            "", null); //$NON-NLS-1$
        Path override = overridePath.isBlank() ? null : Path.of(overridePath.trim());
        LocalIssueProvider provider =
            new LocalIssueProvider(projectKey, projectRoot, stateDir, override, new ProcessAnalyzeRunner());
        // Local component keys are <projectKey>:src/... already project-relative, so the mapping key is the
        // same effective key fed to the provider and the mapping prefix is always empty (the binding prefix,
        // which describes a server repository layout, must not be stripped from local paths).
        return Optional.of(new ProjectRefreshInputs(project, binding, null, provider, projectKey, "")); //$NON-NLS-1$
    }
}
