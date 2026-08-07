/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.ui.resources;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests for {@link WorkspaceFiles}, which reconciles one file handle with the file system.
 *
 * <p>Regression tests for review minor M11: this reconciliation used to live inside marker synchronization,
 * which is skipped entirely when the <em>Show issues in the standard Problems view</em> preference is off. A
 * file written behind the workspace's back - a git checkout run outside EDT - then stayed invisible to
 * editor navigation and to the quick-suppress action, both of which gave up on {@code !file.exists()}.
 */
public class WorkspaceFilesTest
{
    private IProject project;

    @Before
    public void setUp() throws CoreException
    {
        project = ResourcesPlugin.getWorkspace().getRoot().getProject("workspace-files-test");
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
    public void fileWrittenBehindTheWorkspacesBackIsFoundAfterTheRefresh() throws CoreException, IOException
    {
        IFile file = project.getFile("src/Module.bsl");
        Path onDisk = project.getLocation().toFile().toPath().resolve("src").resolve("Module.bsl");
        Files.createDirectories(onDisk.getParent());
        Files.writeString(onDisk, "// written outside EDT");

        assertFalse("precondition: the workspace does not know the file yet", file.exists());
        assertTrue(WorkspaceFiles.existsAfterRefresh(file));
        assertTrue("the file must now be part of the resource tree", file.exists());
    }

    @Test
    public void aFileThatIsAbsentFromDiskTooStaysAbsent() throws CoreException
    {
        IFile file = project.getFile("src/Gone.bsl");

        assertFalse(WorkspaceFiles.existsAfterRefresh(file));
    }

    @Test
    public void aFileTheWorkspaceAlreadyKnowsNeedsNoRefresh() throws CoreException
    {
        IFile file = project.getFile("Known.bsl");
        file.create(new ByteArrayInputStream(new byte[0]), true, new NullProgressMonitor());

        assertTrue(WorkspaceFiles.existsAfterRefresh(file));
    }
}
