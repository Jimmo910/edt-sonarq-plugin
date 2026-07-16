/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.ui.settings;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ProjectScope;
import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.osgi.service.prefs.BackingStoreException;

import ru.jimmo.edt.sonarq.core.settings.ProjectBinding;
import ru.jimmo.edt.sonarq.ui.SonarqPlugin;

/** Persists the SonarQube binding of an EDT project in the project's preference scope. */
public final class ProjectBindingStore
{
    private static final String KEY_PROJECT_KEY = "projectKey"; //$NON-NLS-1$

    private static final String KEY_BRANCH = "branch"; //$NON-NLS-1$

    private static final String KEY_PATH_PREFIX = "pathPrefix"; //$NON-NLS-1$

    /**
     * Loads the SonarQube binding of a project.
     *
     * @param project the EDT project, not {@code null}
     * @return the binding, {@link ProjectBinding#isConfigured() not configured} when none is stored
     */
    public ProjectBinding load(IProject project)
    {
        IEclipsePreferences node = new ProjectScope(project).getNode(SonarqPlugin.PLUGIN_ID);
        String projectKey = node.get(KEY_PROJECT_KEY, ""); //$NON-NLS-1$
        String branchOverride = node.get(KEY_BRANCH, ""); //$NON-NLS-1$
        String pathPrefix = node.get(KEY_PATH_PREFIX, ""); //$NON-NLS-1$
        return new ProjectBinding(projectKey, branchOverride, pathPrefix);
    }

    /**
     * Saves the SonarQube binding of a project.
     *
     * @param project the EDT project, not {@code null}
     * @param binding the binding to store, not {@code null}
     * @throws BackingStoreException when the preferences cannot be flushed
     */
    public void save(IProject project, ProjectBinding binding) throws BackingStoreException
    {
        IEclipsePreferences node = new ProjectScope(project).getNode(SonarqPlugin.PLUGIN_ID);
        node.put(KEY_PROJECT_KEY, binding.projectKey());
        node.put(KEY_BRANCH, binding.branchOverride());
        node.put(KEY_PATH_PREFIX, binding.pathPrefix());
        node.flush();
    }
}
