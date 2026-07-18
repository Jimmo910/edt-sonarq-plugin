/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.ui.suppress;

import org.eclipse.core.filebuffers.FileBuffers;
import org.eclipse.core.filebuffers.ITextFileBuffer;
import org.eclipse.core.filebuffers.ITextFileBufferManager;
import org.eclipse.core.filebuffers.LocationKind;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.Adapters;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.part.FileEditorInput;
import org.eclipse.ui.texteditor.ITextEditor;

import ru.jimmo.edt.sonarq.core.suppress.BslSuppression;

/**
 * Applies a {@link BslSuppression#insert} edit to a workspace file (issue #7).
 *
 * <p>When the file is already open in a text editor on the given page, the edit is applied directly to
 * that editor's document, so the change is live and undoable through the editor's own undo stack. Otherwise
 * - and always as the reliable fallback when no matching open editor is found - the edit is applied through
 * a connected {@link ITextFileBufferManager}, which reads and writes the file with its own encoding/BOM
 * handling, so this class never hand-rolls file I/O.
 */
public final class SuppressionApplier
{
    private SuppressionApplier()
    {
    }

    /**
     * Suppresses {@code ruleKey} on {@code line} of {@code file}.
     *
     * @param file the file to edit, not {@code null}; must {@link IFile#exists()}
     * @param line the 1-based line to wrap, must be {@code > 0}
     * @param ruleKey the rule key (bare or {@code bsl:}-prefixed) to suppress, not {@code null}
     * @param page the active workbench page to search for an already-open editor of {@code file}, or
     *     {@code null} to always go through the file-buffer path
     * @throws CoreException when connecting to, or committing, the file buffer fails
     * @throws BadLocationException when {@code line} is out of the document's range
     */
    public static void apply(IFile file, int line, String ruleKey, IWorkbenchPage page)
        throws CoreException, BadLocationException
    {
        IDocument openDocument = openEditorDocument(file, page);
        if (openDocument != null)
        {
            BslSuppression.insert(openDocument, line, ruleKey);
            return;
        }
        applyToFileBuffer(file, line, ruleKey);
    }

    private static IDocument openEditorDocument(IFile file, IWorkbenchPage page)
    {
        if (page == null)
        {
            return null;
        }
        IEditorInput input = new FileEditorInput(file);
        IEditorPart editor = page.findEditor(input);
        if (editor == null)
        {
            return null;
        }
        ITextEditor textEditor = Adapters.adapt(editor, ITextEditor.class);
        return textEditor != null ? textEditor.getDocumentProvider().getDocument(textEditor.getEditorInput()) : null;
    }

    private static void applyToFileBuffer(IFile file, int line, String ruleKey)
        throws CoreException, BadLocationException
    {
        ITextFileBufferManager manager = FileBuffers.getTextFileBufferManager();
        IProgressMonitor monitor = new NullProgressMonitor();
        manager.connect(file.getFullPath(), LocationKind.IFILE, monitor);
        try
        {
            ITextFileBuffer buffer = manager.getTextFileBuffer(file.getFullPath(), LocationKind.IFILE);
            BslSuppression.insert(buffer.getDocument(), line, ruleKey);
            buffer.commit(monitor, false);
        }
        finally
        {
            manager.disconnect(file.getFullPath(), LocationKind.IFILE, monitor);
        }
    }
}
