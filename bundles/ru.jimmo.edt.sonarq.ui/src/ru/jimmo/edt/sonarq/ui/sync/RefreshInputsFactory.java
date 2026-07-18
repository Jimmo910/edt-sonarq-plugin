/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.ui.sync;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.preferences.IPreferencesService;

import ru.jimmo.edt.sonarq.core.client.SonarConnection;
import ru.jimmo.edt.sonarq.core.client.SonarHttpClient;
import ru.jimmo.edt.sonarq.core.localanalysis.BslConfigWriter;
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
        Path configPath = resolveConfigPath(stateDir, service, binding.subsystems());
        int maxHeapGb = resolveMaxHeapGb(service);
        LocalIssueProvider provider = new LocalIssueProvider(projectKey, projectRoot, stateDir, override,
            configPath, binding.baseBranch(), maxHeapGb, new ProcessAnalyzeRunner());
        // Local component keys are <projectKey>:src/... already project-relative, so the mapping key is the
        // same effective key fed to the provider and the mapping prefix is always empty (the binding prefix,
        // which describes a server repository layout, must not be stripped from local paths).
        return Optional.of(new ProjectRefreshInputs(project, binding, null, provider, projectKey, "")); //$NON-NLS-1$
    }

    /**
     * Resolves {@link PreferenceConstants#PREF_BSL_LS_MAX_HEAP_GB}, clamped to a sane floor.
     *
     * <p>{@link IPreferencesService#getInt} already falls back to
     * {@link PreferenceConstants#DEFAULT_BSL_LS_MAX_HEAP_GB} when the stored value is missing or not a
     * number; the extra clamp only guards against a stored value that parses fine but is zero or negative
     * (for example, a hand-edited preferences file), so the language server is never handed a nonsensical
     * heap limit.
     *
     * @param service the preferences service to read the preference from, not {@code null}
     * @return the maximum heap, in gigabytes, always at least 1
     */
    private static int resolveMaxHeapGb(IPreferencesService service)
    {
        int stored = service.getInt(SonarqPlugin.PLUGIN_ID, PreferenceConstants.PREF_BSL_LS_MAX_HEAP_GB,
            PreferenceConstants.DEFAULT_BSL_LS_MAX_HEAP_GB, null);
        return Math.max(1, stored);
    }

    /**
     * Resolves the generated checks configuration path from
     * {@link PreferenceConstants#PREF_DISABLED_BSL_DIAGNOSTICS} and {@code includeSubsystems}, writing it
     * under the plugin state directory.
     *
     * <p>A failure to write the configuration file must never fail the refresh: it is logged and
     * {@code null} is returned, so the analysis simply runs with every diagnostic enabled and no subsystem
     * restriction instead.
     *
     * @param stateDir the plugin state directory to write the configuration file under, not {@code null}
     * @param service the preferences service to read the disabled-diagnostics preference from, not
     *     {@code null}
     * @param includeSubsystems the subsystem names to restrict analysis to, from the project binding, not
     *     {@code null}, empty means no restriction
     * @return the generated configuration path, or {@code null} when neither diagnostics are disabled nor
     *     subsystems are restricted, or the configuration file could not be written
     */
    private static Path resolveConfigPath(Path stateDir, IPreferencesService service,
        Collection<String> includeSubsystems)
    {
        String stored = service.getString(SonarqPlugin.PLUGIN_ID,
            PreferenceConstants.PREF_DISABLED_BSL_DIAGNOSTICS, "", null); //$NON-NLS-1$
        Set<String> disabled = new HashSet<>();
        for (String key : stored.split(",")) //$NON-NLS-1$
        {
            String trimmed = key.trim();
            if (!trimmed.isEmpty())
            {
                disabled.add(trimmed);
            }
        }
        try
        {
            return BslConfigWriter.write(stateDir, disabled, includeSubsystems);
        }
        catch (IOException e)
        {
            SonarqPlugin.getInstance().getLog().error(e.getMessage(), e);
            return null;
        }
    }
}
