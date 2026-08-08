/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.ui.suppress;

import static org.junit.Assert.assertEquals;

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
import org.eclipse.jface.text.Document;
import org.eclipse.jface.text.IDocument;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import ru.jimmo.edt.sonarq.core.suppress.LineAnchor;
import ru.jimmo.edt.sonarq.core.suppress.SuppressionOutcome;
import ru.jimmo.edt.sonarq.core.suppress.SuppressionResult;

/**
 * Tests for {@link SuppressionApplier}'s file-buffer path - the one taken when the file is not open in an
 * editor, which is also the only path reachable without a workbench, so these run headless with
 * {@code page == null} - plus the open-editor path through its
 * {@link SuppressionApplier#applyToOpenDocument} seam.
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
        SuppressionResult result = SuppressionApplier.apply(file, 2, "bsl:MagicNumber", anchorOfLine(2), null);

        assertEquals(SuppressionOutcome.INSERTED, result.outcome());
        assertEquals("the caller has to know which line grew the comment pair", 2, result.line());
        assertEquals(SUPPRESSED, onDisk());
    }

    @Test
    public void reportsNoInsertionWhenTheLineIsAlreadySuppressed() throws Exception
    {
        String anchor = anchorOfLine(2);
        assertEquals(SuppressionOutcome.INSERTED,
            SuppressionApplier.apply(file, 2, "MagicNumber", anchor, null).outcome());

        // The same issue again, with its pre-edit line number: the anchor finds the line it just wrapped.
        assertEquals(SuppressionOutcome.ALREADY_SUPPRESSED,
            SuppressionApplier.apply(file, 2, "MagicNumber", anchor, null).outcome());

        assertEquals(SUPPRESSED, onDisk());
    }

    /**
     * Committing a buffer that someone else left dirty would write their unsaved changes to disk on the
     * user's behalf, and the line number handed to the applier describes the last analyzed - i.e. saved -
     * content, so it need not match the modified buffer at all. The applier must refuse both the edit and
     * the commit, and report it so no caller shifts its issue lines for it.
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

            assertEquals(SuppressionOutcome.UNSAVED_CHANGES,
                SuppressionApplier.apply(file, 2, "MagicNumber", anchorOfLine(2), null).outcome());

            assertEquals(-1, buffer.getDocument().get().indexOf("BSLLS"));
            assertEquals(SOURCE, onDisk());
        }
        finally
        {
            manager.disconnect(file.getFullPath(), LocationKind.IFILE, new NullProgressMonitor());
        }
    }

    /**
     * The whole point of the anchor, driven end to end on a real file: a first suppression grows the file by
     * two lines, and a server-mode refresh then restores the line numbers SonarQube recorded at its last
     * analysis - so the second issue arrives pointing two lines above its own statement. Trusting that number
     * wraps {@code Б = 2;}'s predecessor; the anchor makes the applier wrap {@code Б = 2;} itself.
     */
    @Test
    public void wrapsTheAnchoredLineAfterARefreshRestoredPreEditLineNumbers() throws Exception
    {
        String source = "Процедура П()\n    А = 1;\n    Б = 2;\n    В = 3;\nКонецПроцедуры\n";
        write(source);
        String firstIssueAnchor = LineAnchor.of(new Document(source), 2);
        String secondIssueAnchor = LineAnchor.of(new Document(source), 3);

        assertEquals(SuppressionOutcome.INSERTED,
            SuppressionApplier.apply(file, 2, "R1", firstIssueAnchor, null).outcome());
        // The refresh puts the second issue back at its pre-edit line 3; it really sits on line 5 now, and
        // that is the line the result has to report.
        SuppressionResult second = SuppressionApplier.apply(file, 3, "R2", secondIssueAnchor, null);
        assertEquals(SuppressionOutcome.INSERTED, second.outcome());
        assertEquals(5, second.line());

        assertEquals("Процедура П()\n"
            + "    // BSLLS:R1-off\n"
            + "    А = 1;\n"
            + "    // BSLLS:R1-on\n"
            + "    // BSLLS:R2-off\n"
            + "    Б = 2;\n"
            + "    // BSLLS:R2-on\n"
            + "    В = 3;\n"
            + "КонецПроцедуры\n", onDisk());
    }

    /**
     * The other half of the contract: when the anchored line is gone - the user rewrote it since the
     * analysis - the file must not change by a single byte, and the refusal must say so.
     */
    @Test
    public void refusesAndLeavesTheFileUntouchedWhenTheAnchoredLineIsGone() throws Exception
    {
        assertEquals(SuppressionOutcome.ANCHOR_NOT_FOUND,
            SuppressionApplier.apply(file, 2, "MagicNumber", LineAnchor.of("    Я = 99;"), null).outcome());

        assertEquals(SOURCE, onDisk());
    }

    /**
     * An issue that could not be fingerprinted (its file was missing when the issues were mapped) is refused,
     * not written blind: the recorded line number is the one thing an unverifiable issue offers, and it is
     * exactly what may not be trusted when the answer is an edit to the user's source.
     */
    @Test
    public void anEmptyAnchorIsRefusedAndWritesNothing() throws Exception
    {
        SuppressionResult result = SuppressionApplier.apply(file, 2, "MagicNumber", LineAnchor.NONE, null);

        assertEquals(SuppressionOutcome.ANCHOR_MISSING, result.outcome());
        assertEquals(SuppressionResult.NO_LINE, result.line());
        assertEquals(SOURCE, onDisk());
    }

    /**
     * The open-editor path used to check nothing at all: an editor holding unsaved changes was edited
     * straight through its document, although its line numbering had already moved away from the analyzed
     * content. It must refuse exactly as the file-buffer path does.
     */
    @Test
    public void refusesWhenTheOpenEditorDocumentIsDirty() throws Exception
    {
        IDocument document = new Document(SOURCE);

        assertEquals(SuppressionOutcome.UNSAVED_CHANGES,
            SuppressionApplier.applyToOpenDocument(document, true, 2, "MagicNumber",
                LineAnchor.of(document, 2)).outcome());

        assertEquals(SOURCE, document.get());
    }

    @Test
    public void editsACleanOpenEditorDocument() throws Exception
    {
        IDocument document = new Document(SOURCE);

        assertEquals(SuppressionOutcome.INSERTED,
            SuppressionApplier.applyToOpenDocument(document, false, 2, "MagicNumber",
                LineAnchor.of(document, 2)).outcome());

        assertEquals(SUPPRESSED, document.get());
    }

    /**
     * The anchor of one line of the pristine module, as the issue mapping records it.
     *
     * @param line1Based the 1-based line number
     * @return the serialized anchor
     */
    private static String anchorOfLine(int line1Based)
    {
        return LineAnchor.of(new Document(SOURCE), line1Based);
    }

    private void write(String content) throws CoreException
    {
        file.setContents(new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)), true, false,
            new NullProgressMonitor());
    }

    private String onDisk() throws IOException
    {
        return Files.readString(file.getLocation().toFile().toPath(), StandardCharsets.UTF_8);
    }
}
