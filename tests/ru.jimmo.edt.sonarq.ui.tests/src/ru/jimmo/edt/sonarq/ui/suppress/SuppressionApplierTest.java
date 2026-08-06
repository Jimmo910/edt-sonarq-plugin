/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.ui.suppress;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.eclipse.core.filebuffers.FileBuffers;
import org.eclipse.core.filebuffers.ITextFileBuffer;
import org.eclipse.core.filebuffers.ITextFileBufferManager;
import org.eclipse.core.filebuffers.LocationKind;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests for {@link SuppressionApplier}'s file-buffer path - the one taken when the file is not open in an
 * editor, which is also the only path reachable without a workbench, so these run headless with
 * {@code page == null}.
 */
public class SuppressionApplierTest
{
    private static final String RELATIVE_PATH = "src/Module.bsl";
    private static final String SOURCE = "Процедура П()\n    А = 1;\nКонецПроцедуры\n";
    private static final String SUPPRESSED = "Процедура П()\n"
        + "    // BSLLS:MagicNumber-off\n"
        + "    А = 1;\n"
        + "    // BSLLS:MagicNumber-on\n"
        + "КонецПроцедуры\n";

    private IProject project;
    private IFile file;

    @Before
    public void setUp() throws CoreException
    {
        project = ResourcesPlugin.getWorkspace().getRoot().getProject("suppression-applier-test");
        if (!project.exists())
        {
            project.create(new NullProgressMonitor());
        }
        project.open(new NullProgressMonitor());
        IFolder folder = project.getFolder("src");
        if (!folder.exists())
        {
            folder.create(true, true, new NullProgressMonitor());
        }
        file = project.getFile(RELATIVE_PATH);
        if (file.exists())
        {
            file.delete(true, new NullProgressMonitor());
        }
        file.create(new ByteArrayInputStream(SOURCE.getBytes(StandardCharsets.UTF_8)), true,
            new NullProgressMonitor());
        file.setCharset(StandardCharsets.UTF_8.name(), new NullProgressMonitor());
    }

    @After
    public void tearDown() throws CoreException
    {
        project.delete(true, true, new NullProgressMonitor());
    }

    @Test
    public void writesTheCommentPairAndReportsTheInsertion() throws Exception
    {
        assertTrue(SuppressionApplier.apply(file, 2, "bsl:MagicNumber", null));

        assertEquals(SUPPRESSED, onDisk());
    }

    @Test
    public void reportsNoInsertionWhenTheLineIsAlreadySuppressed() throws Exception
    {
        assertTrue(SuppressionApplier.apply(file, 2, "MagicNumber", null));

        // The line moved down by one, so this is the same code line, already wrapped by the call above.
        assertFalse(SuppressionApplier.apply(file, 3, "MagicNumber", null));

        assertEquals(SUPPRESSED, onDisk());
    }

    /**
     * Committing a buffer that someone else left dirty would write their unsaved changes to disk on the
     * user's behalf, and the line number handed to the applier describes the last analyzed - i.e. saved -
     * content, so it need not match the modified buffer at all. The applier must refuse both the edit and
     * the commit, and report it as "not inserted" so no caller shifts its issue lines for it.
     */
    @Test
    public void refusesToWriteWhenTheBufferHasUnsavedChanges() throws Exception
    {
        ITextFileBufferManager manager = FileBuffers.getTextFileBufferManager();
        manager.connect(file.getFullPath(), LocationKind.IFILE, new NullProgressMonitor());
        try
        {
            ITextFileBuffer buffer = manager.getTextFileBuffer(file.getFullPath(), LocationKind.IFILE);
            buffer.getDocument().replace(0, 0, "// someone else is editing this\n");
            assertTrue(buffer.isDirty());

            assertFalse(SuppressionApplier.apply(file, 2, "MagicNumber", null));

            assertFalse(buffer.getDocument().get().contains("BSLLS"));
            assertEquals(SOURCE, onDisk());
        }
        finally
        {
            manager.disconnect(file.getFullPath(), LocationKind.IFILE, new NullProgressMonitor());
        }
    }

    private String onDisk() throws IOException
    {
        return Files.readString(file.getLocation().toFile().toPath(), StandardCharsets.UTF_8);
    }
}
