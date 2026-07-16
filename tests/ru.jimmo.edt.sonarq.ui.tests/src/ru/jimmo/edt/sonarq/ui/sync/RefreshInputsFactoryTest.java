/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.ui.sync;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Optional;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.osgi.service.prefs.BackingStoreException;

import ru.jimmo.edt.sonarq.core.localanalysis.LocalIssueProvider;
import ru.jimmo.edt.sonarq.core.model.SonarIssue;
import ru.jimmo.edt.sonarq.core.model.SonarIssueType;
import ru.jimmo.edt.sonarq.core.model.SonarSeverity;
import ru.jimmo.edt.sonarq.core.provider.ServerIssueProvider;
import ru.jimmo.edt.sonarq.core.settings.ProjectBinding;
import ru.jimmo.edt.sonarq.ui.SonarqPlugin;
import ru.jimmo.edt.sonarq.ui.settings.PreferenceConstants;
import ru.jimmo.edt.sonarq.ui.settings.ProjectBindingStore;
import ru.jimmo.edt.sonarq.ui.views.IssueEntry;
import ru.jimmo.edt.sonarq.ui.views.IssueTreeBuilder;

/** Tests for {@link RefreshInputsFactory}. */
public class RefreshInputsFactoryTest
{
    private IProject project;

    private String savedUrl;

    private String savedMode;

    @Before
    public void setUp() throws CoreException, BackingStoreException
    {
        IEclipsePreferences node = InstanceScope.INSTANCE.getNode(SonarqPlugin.PLUGIN_ID);
        savedUrl = node.get(PreferenceConstants.PREF_SERVER_URL, ""); //$NON-NLS-1$
        savedMode = node.get(PreferenceConstants.PREF_MODE, null);
        node.remove(PreferenceConstants.PREF_SERVER_URL);
        node.flush();
        project = ResourcesPlugin.getWorkspace().getRoot().getProject("refresh-inputs-test"); //$NON-NLS-1$
        if (!project.exists())
        {
            project.create(new NullProgressMonitor());
        }
        project.open(new NullProgressMonitor());
    }

    @After
    public void tearDown() throws CoreException, BackingStoreException
    {
        IEclipsePreferences node = InstanceScope.INSTANCE.getNode(SonarqPlugin.PLUGIN_ID);
        if (savedUrl.isEmpty())
        {
            node.remove(PreferenceConstants.PREF_SERVER_URL);
        }
        else
        {
            node.put(PreferenceConstants.PREF_SERVER_URL, savedUrl);
        }
        if (savedMode == null)
        {
            node.remove(PreferenceConstants.PREF_MODE);
        }
        else
        {
            node.put(PreferenceConstants.PREF_MODE, savedMode);
        }
        node.flush();
        if (project.exists())
        {
            project.delete(true, true, new NullProgressMonitor());
        }
    }

    @Test
    public void absentProjectYieldsEmpty()
    {
        IProject absent = ResourcesPlugin.getWorkspace().getRoot().getProject("no-such-project"); //$NON-NLS-1$
        assertTrue(RefreshInputsFactory.create(absent).isEmpty());
    }

    @Test
    public void closedProjectYieldsEmpty() throws CoreException
    {
        project.close(new NullProgressMonitor());
        assertTrue(RefreshInputsFactory.create(project).isEmpty());
    }

    @Test
    public void openProjectWithoutBindingYieldsEmpty()
    {
        assertTrue(RefreshInputsFactory.create(project).isEmpty());
    }

    @Test
    public void bindingWithoutServerUrlYieldsEmpty() throws BackingStoreException
    {
        ProjectBinding binding = new ProjectBinding("proj:key", "", "conf"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        new ProjectBindingStore().save(project, binding);
        assertTrue(RefreshInputsFactory.create(project).isEmpty());
    }

    @Test
    public void bindingWithServerUrlYieldsInputs() throws BackingStoreException
    {
        ProjectBinding binding = new ProjectBinding("proj:key", "", "conf"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        new ProjectBindingStore().save(project, binding);
        IEclipsePreferences node = InstanceScope.INSTANCE.getNode(SonarqPlugin.PLUGIN_ID);
        node.put(PreferenceConstants.PREF_SERVER_URL, "https://sonar.example.com"); //$NON-NLS-1$
        node.flush();

        Optional<ProjectRefreshInputs> inputs = RefreshInputsFactory.create(project);
        assertTrue(inputs.isPresent());
        assertEquals("proj:key", inputs.get().binding().projectKey()); //$NON-NLS-1$
        assertEquals("conf", inputs.get().binding().pathPrefix()); //$NON-NLS-1$
        assertEquals(project, inputs.get().project());
        assertNotNull(inputs.get().connection());
        assertNotNull(inputs.get().provider());
    }

    @Test
    public void serverModeYieldsServerIssueProvider() throws BackingStoreException
    {
        // The default mode is server, so no explicit PREF_MODE is written here.
        ProjectBinding binding = new ProjectBinding("proj:key", "", "conf"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        new ProjectBindingStore().save(project, binding);
        IEclipsePreferences node = InstanceScope.INSTANCE.getNode(SonarqPlugin.PLUGIN_ID);
        node.put(PreferenceConstants.PREF_SERVER_URL, "https://sonar.example.com"); //$NON-NLS-1$
        node.flush();

        Optional<ProjectRefreshInputs> inputs = RefreshInputsFactory.create(project);
        assertTrue(inputs.isPresent());
        assertTrue(inputs.get().provider() instanceof ServerIssueProvider);
        assertNotNull(inputs.get().connection());
    }

    @Test
    public void localModeYieldsInputsWithoutServerUrl() throws BackingStoreException
    {
        IEclipsePreferences node = InstanceScope.INSTANCE.getNode(SonarqPlugin.PLUGIN_ID);
        node.put(PreferenceConstants.PREF_MODE, PreferenceConstants.MODE_LOCAL);
        node.remove(PreferenceConstants.PREF_SERVER_URL);
        node.flush();
        ProjectBinding binding = new ProjectBinding("proj:key", "", "conf"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        new ProjectBindingStore().save(project, binding);

        Optional<ProjectRefreshInputs> inputs = RefreshInputsFactory.create(project);
        assertTrue(inputs.isPresent());
        assertNull(inputs.get().connection());
        assertTrue(inputs.get().provider() instanceof LocalIssueProvider);
        LocalIssueProvider provider = (LocalIssueProvider)inputs.get().provider();
        assertFalse(provider.branchAnalysisSupported());
        assertEquals("proj:key", provider.projectKey()); //$NON-NLS-1$
    }

    @Test
    public void localModeEmptyBindingUsesProjectNameAsKey() throws BackingStoreException
    {
        IEclipsePreferences node = InstanceScope.INSTANCE.getNode(SonarqPlugin.PLUGIN_ID);
        node.put(PreferenceConstants.PREF_MODE, PreferenceConstants.MODE_LOCAL);
        node.flush();

        Optional<ProjectRefreshInputs> inputs = RefreshInputsFactory.create(project);
        assertTrue(inputs.isPresent());
        assertEquals("", inputs.get().binding().projectKey()); //$NON-NLS-1$
        assertEquals(project.getName(), ((LocalIssueProvider)inputs.get().provider()).projectKey());
    }

    @Test
    public void localModeEmptyBindingMapsComponentKeyToRelativePath() throws BackingStoreException
    {
        IEclipsePreferences node = InstanceScope.INSTANCE.getNode(SonarqPlugin.PLUGIN_ID);
        node.put(PreferenceConstants.PREF_MODE, PreferenceConstants.MODE_LOCAL);
        node.flush();

        ProjectRefreshInputs inputs = RefreshInputsFactory.create(project).orElseThrow();
        assertEquals(project.getName(), inputs.mappingProjectKey());
        assertEquals("", inputs.mappingPathPrefix()); //$NON-NLS-1$

        // End-to-end seam: a local component key <projectName>:src/M.bsl must map back to src/M.bsl.
        SonarIssue issue = new SonarIssue("k", "MethodSize", SonarSeverity.MAJOR, //$NON-NLS-1$ //$NON-NLS-2$
            SonarIssueType.CODE_SMELL, project.getName() + ":src/M.bsl", "Too long", 1); //$NON-NLS-1$ //$NON-NLS-2$
        List<IssueEntry> entries =
            IssueTreeBuilder.toEntries(List.of(issue), inputs.mappingProjectKey(), inputs.mappingPathPrefix());
        assertNotNull(entries.get(0).relativePath());
        assertEquals("src/M.bsl", entries.get(0).relativePath()); //$NON-NLS-1$
    }

    @Test
    public void localModeIgnoresBindingPathPrefixForMapping() throws BackingStoreException
    {
        IEclipsePreferences node = InstanceScope.INSTANCE.getNode(SonarqPlugin.PLUGIN_ID);
        node.put(PreferenceConstants.PREF_MODE, PreferenceConstants.MODE_LOCAL);
        node.flush();
        new ProjectBindingStore().save(project,
            new ProjectBinding("K", "", "conf")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        ProjectRefreshInputs inputs = RefreshInputsFactory.create(project).orElseThrow();
        assertEquals("K", inputs.mappingProjectKey()); //$NON-NLS-1$
        assertEquals("", inputs.mappingPathPrefix()); //$NON-NLS-1$
    }

    @Test
    public void serverModeMappingFieldsMirrorBinding() throws BackingStoreException
    {
        new ProjectBindingStore().save(project,
            new ProjectBinding("proj:key", "", "conf")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        IEclipsePreferences node = InstanceScope.INSTANCE.getNode(SonarqPlugin.PLUGIN_ID);
        node.put(PreferenceConstants.PREF_SERVER_URL, "https://sonar.example.com"); //$NON-NLS-1$
        node.flush();

        ProjectRefreshInputs inputs = RefreshInputsFactory.create(project).orElseThrow();
        assertEquals("proj:key", inputs.mappingProjectKey()); //$NON-NLS-1$
        assertEquals("conf", inputs.mappingPathPrefix()); //$NON-NLS-1$
    }
}
