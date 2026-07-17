/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.ui.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.osgi.service.prefs.BackingStoreException;

import ru.jimmo.edt.sonarq.core.settings.ProjectBinding;

/** Tests for {@link ProjectBindingStore}. */
public class ProjectBindingStoreTest
{
    private IProject project;

    @Before
    public void setUp() throws CoreException
    {
        project = ResourcesPlugin.getWorkspace().getRoot().getProject("binding-test");
        if (!project.exists())
        {
            project.create(new NullProgressMonitor());
        }
        project.open(new NullProgressMonitor());
    }

    @After
    public void tearDown() throws CoreException
    {
        project.delete(true, true, new NullProgressMonitor());
    }

    @Test
    public void roundTripsBinding() throws BackingStoreException
    {
        ProjectBindingStore store = new ProjectBindingStore();
        store.save(project, new ProjectBinding("my:key", "release/1", "conf"));
        ProjectBinding loaded = store.load(project);
        assertEquals("my:key", loaded.projectKey());
        assertEquals("release/1", loaded.branchOverride());
        assertEquals("conf", loaded.pathPrefix());
        assertTrue(loaded.isConfigured());
    }

    @Test
    public void missingBindingIsNotConfigured()
    {
        ProjectBinding loaded = new ProjectBindingStore().load(project);
        assertEquals("", loaded.projectKey());
        assertFalse(loaded.isConfigured());
    }

    @Test
    public void roundTripsScopeFields() throws Exception
    {
        ProjectBindingStore store = new ProjectBindingStore();
        store.save(project, new ProjectBinding("my:key", "release/1", "conf", "vendor/base",
            List.of("СтандартныеПодсистемы", "ЮТДвижок")));

        ProjectBinding loaded = store.load(project);

        assertEquals("vendor/base", loaded.baseBranch());
        assertEquals(List.of("СтандартныеПодсистемы", "ЮТДвижок"), loaded.subsystems());
    }

    @Test
    public void loadsLegacyBindingWithoutScopeAsEmptyScope() throws Exception
    {
        new ProjectBindingStore().save(project, new ProjectBinding("k", "", "")); // 3-arg convenience

        ProjectBinding loaded = new ProjectBindingStore().load(project);

        assertEquals("", loaded.baseBranch());
        assertTrue(loaded.subsystems().isEmpty());
    }
}
