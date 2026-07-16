/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.ui.sync;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

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

import ru.jimmo.edt.sonarq.core.settings.ProjectBinding;
import ru.jimmo.edt.sonarq.ui.SonarqPlugin;
import ru.jimmo.edt.sonarq.ui.settings.PreferenceConstants;
import ru.jimmo.edt.sonarq.ui.settings.ProjectBindingStore;

/** Tests for {@link RefreshInputsFactory}. */
public class RefreshInputsFactoryTest
{
    private IProject project;

    private String savedUrl;

    @Before
    public void setUp() throws CoreException, BackingStoreException
    {
        IEclipsePreferences node = InstanceScope.INSTANCE.getNode(SonarqPlugin.PLUGIN_ID);
        savedUrl = node.get(PreferenceConstants.PREF_SERVER_URL, ""); //$NON-NLS-1$
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
}
