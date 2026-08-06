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
import org.eclipse.core.runtime.Platform;
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
 * handling, so this class never hand-rolls file I/O. That fallback never commits a buffer that already holds
 * someone else's unsaved changes.
 */
public final class SuppressionApplier
{
    private SuppressionApplier()
    {
    }

    /**
     * Suppresses {@code ruleKey} on {@code line} of {@code file}.
     *
     * <p>Reports whether anything was actually written, so a caller that adjusts its own in-memory line
     * numbers afterwards (see {@link ru.jimmo.edt.sonarq.core.suppress.SuppressionLineShift}) only does so
     * when the file really changed. Nothing is written when {@link BslSuppression#insert}'s own guards make
     * the edit a no-op, or when the file-buffer path finds unsaved changes it must not commit (see
     * {@link #applyToFileBuffer}).
     *
     * @param file the file to edit, not {@code null}; must {@link IFile#exists()}
     * @param line the 1-based line to wrap, must be {@code > 0}
     * @param ruleKey the rule key (bare or {@code bsl:}-prefixed) to suppress, not {@code null}
     * @param page the active workbench page to search for an already-open editor of {@code file}, or
     *     {@code null} to always go through the file-buffer path
     * @return {@code true} when the suppression comments were inserted, {@code false} when the call was a
     *     no-op and neither the document nor the file changed
     * @throws CoreException when connecting to, or committing, the file buffer fails
     * @throws BadLocationException when {@code line} is out of the document's range
     */
    public static boolean apply(IFile file, int line, String ruleKey, IWorkbenchPage page)
        throws CoreException, BadLocationException
    {
        IDocument openDocument = openEditorDocument(file, page);
        if (openDocument != null)
        {
            return BslSuppression.insert(openDocument, line, ruleKey);
        }
        return applyToFileBuffer(file, line, ruleKey);
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

    /**
     * Applies the edit through a connected text file buffer, and commits it to disk.
     *
     * <p>Refuses to touch a buffer that is already dirty - i.e. something else holds unsaved changes for
     * this file. Committing then would silently write those foreign changes to disk on the user's behalf,
     * and the line number handed to this class comes from the last analysis of the <em>saved</em> file, so
     * it need not point at the same code in the modified buffer. The refusal is only logged: this path runs
     * from a quick fix or a context-menu action that may be driven in the background, where this project's
     * rules forbid blocking on a modal dialog.
     *
     * @param file the file to edit, not {@code null}
     * @param line the 1-based line to wrap
     * @param ruleKey the rule key to suppress, not {@code null}
     * @return {@code true} when the comments were inserted and committed, {@code false} on a refused or
     *     no-op edit
     * @throws CoreException when connecting to, or committing, the file buffer fails
     * @throws BadLocationException when {@code line} is out of the document's range
     */
    private static boolean applyToFileBuffer(IFile file, int line, String ruleKey)
        throws CoreException, BadLocationException
    {
        ITextFileBufferManager manager = FileBuffers.getTextFileBufferManager();
        IProgressMonitor monitor = new NullProgressMonitor();
        manager.connect(file.getFullPath(), LocationKind.IFILE, monitor);
        try
        {
            ITextFileBuffer buffer = manager.getTextFileBuffer(file.getFullPath(), LocationKind.IFILE);
            if (buffer == null)
            {
                logRefusal(file, ruleKey, "no text file buffer is available for it"); //$NON-NLS-1$
                return false;
            }
            if (buffer.isDirty())
            {
                logRefusal(file, ruleKey, "it has unsaved changes; save it and try again"); //$NON-NLS-1$
                return false;
            }
            boolean inserted = BslSuppression.insert(buffer.getDocument(), line, ruleKey);
            if (inserted)
            {
                buffer.commit(monitor, false);
            }
            return inserted;
        }
        finally
        {
            manager.disconnect(file.getFullPath(), LocationKind.IFILE, monitor);
        }
    }

    /**
     * Logs a refused suppression instead of raising it in the UI: this path can run unattended (a background
     * refresh's context menu, a quick fix), where a modal dialog would block.
     *
     * @param file the file that was left untouched, not {@code null}
     * @param ruleKey the rule that was not suppressed, not {@code null}
     * @param reason the untranslated reason, not {@code null}
     */
    private static void logRefusal(IFile file, String ruleKey, String reason)
    {
        String message = "Not suppressing " + ruleKey //$NON-NLS-1$
            + " in " + file.getFullPath() //$NON-NLS-1$
            + ": " + reason; //$NON-NLS-1$
        Platform.getLog(SuppressionApplier.class).warn(message);
    }
}
