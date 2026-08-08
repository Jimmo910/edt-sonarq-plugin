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
import ru.jimmo.edt.sonarq.core.suppress.SuppressionOutcome;
import ru.jimmo.edt.sonarq.core.suppress.SuppressionResult;

/**
 * Applies a {@link BslSuppression#insert} edit to a workspace file (issue #7).
 *
 * <p>When the file is already open in a text editor on the given page, the edit is applied directly to
 * that editor's document, so the change is live and undoable through the editor's own undo stack. Otherwise
 * - and always as the reliable fallback when no matching open editor is found - the edit is applied through
 * a connected {@link ITextFileBufferManager}, which reads and writes the file with its own encoding/BOM
 * handling, so this class never hand-rolls file I/O.
 *
 * <p>Neither path touches a file with unsaved changes: the buffer path would commit somebody else's edits to
 * disk on the user's behalf, and both paths would be editing content that no analysis has ever seen.
 */
public final class SuppressionApplier
{
    private SuppressionApplier()
    {
    }

    /**
     * Suppresses {@code ruleKey} on the line of {@code file} that still carries {@code lineAnchor}.
     *
     * <p>Reports what happened <em>and where</em>, so a caller that adjusts its own in-memory line numbers
     * afterwards (see {@link ru.jimmo.edt.sonarq.core.suppress.SuppressionLineShift}) only does so when the
     * file really changed, does it around the line the comments actually went in at - which the anchor may
     * well have moved away from the recorded one - and can tell the user why nothing happened otherwise.
     * Nothing is written when {@link BslSuppression#insert} refuses the line, or when either path finds
     * unsaved changes it must not commit or edit.
     *
     * @param file the file to edit, not {@code null}; must {@link IFile#exists()}
     * @param line the 1-based line recorded for the issue, must be {@code > 0}
     * @param ruleKey the rule key (bare or {@code bsl:}-prefixed) to suppress, not {@code null}
     * @param lineAnchor the anchor recorded for the flagged line, not {@code null}; empty is refused, since
     *     an unverifiable line may not be rewritten
     * @param page the active workbench page to search for an already-open editor of {@code file}, or
     *     {@code null} to always go through the file-buffer path
     * @return what the attempt did, and the line it edited (see {@link SuppressionResult}), never
     *     {@code null}
     * @throws CoreException when connecting to, or committing, the file buffer fails
     * @throws BadLocationException when {@code line} is out of the document's range
     */
    public static SuppressionResult apply(IFile file, int line, String ruleKey, String lineAnchor,
        IWorkbenchPage page) throws CoreException, BadLocationException
    {
        SuppressionResult result = applyToBestPath(file, line, ruleKey, lineAnchor, page);
        if (!result.inserted())
        {
            logRefusal(file, ruleKey, result.outcome());
        }
        return result;
    }

    /**
     * Applies the edit to the open editor's document when there is one, and through a file buffer otherwise.
     *
     * @param file the file to edit, not {@code null}
     * @param line the 1-based line recorded for the issue
     * @param ruleKey the rule key to suppress, not {@code null}
     * @param lineAnchor the anchor recorded for the flagged line, not {@code null}
     * @param page the workbench page to look for an open editor in, or {@code null}
     * @return what the attempt did, and the line it edited (see {@link SuppressionResult}), never
     *     {@code null}
     * @throws CoreException when connecting to, or committing, the file buffer fails
     * @throws BadLocationException when {@code line} is out of the document's range
     */
    private static SuppressionResult applyToBestPath(IFile file, int line, String ruleKey, String lineAnchor,
        IWorkbenchPage page) throws CoreException, BadLocationException
    {
        OpenDocument open = openEditorDocument(file, page);
        if (open != null)
        {
            return applyToOpenDocument(open.document(), open.dirty(), line, ruleKey, lineAnchor);
        }
        return applyToFileBuffer(file, line, ruleKey, lineAnchor);
    }

    /**
     * Applies the edit to the document of an open text editor, unless that editor is dirty.
     *
     * <p>A dirty editor is refused for the same reason a dirty file buffer is: the line number and the anchor
     * handed to this class describe the file as the last analysis saw it - i.e. as it was saved - so unsaved
     * edits above the issue have already invalidated the number, and unsaved edits <em>to</em> the issue's
     * line have invalidated the anchor. This path used to check nothing at all, which is how an untouched
     * saved file and a heavily edited buffer were treated identically.
     *
     * <p>Package-private, and taking the dirty flag rather than the editor, so the refusal can be tested
     * headless: the editor path itself needs a running workbench, which the test fragment does not have.
     *
     * @param document the open editor's document, not {@code null}
     * @param dirty whether the editor holds unsaved changes
     * @param line the 1-based line recorded for the issue
     * @param ruleKey the rule key to suppress, not {@code null}
     * @param lineAnchor the anchor recorded for the flagged line, not {@code null}
     * @return what the attempt did, and the line it edited (see {@link SuppressionResult}), never
     *     {@code null}
     * @throws BadLocationException when {@code line} is out of the document's range
     */
    static SuppressionResult applyToOpenDocument(IDocument document, boolean dirty, int line, String ruleKey,
        String lineAnchor) throws BadLocationException
    {
        if (dirty)
        {
            return SuppressionResult.refused(SuppressionOutcome.UNSAVED_CHANGES);
        }
        return BslSuppression.insert(document, line, ruleKey, lineAnchor);
    }

    /**
     * Finds the document of an already-open text editor for the file, together with its dirty state.
     *
     * @param file the file to look for, not {@code null}
     * @param page the workbench page to search, or {@code null}
     * @return the open document, or {@code null} when the file is not open in a text editor
     */
    private static OpenDocument openEditorDocument(IFile file, IWorkbenchPage page)
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
        if (textEditor == null)
        {
            return null;
        }
        IDocument document = textEditor.getDocumentProvider().getDocument(textEditor.getEditorInput());
        return document != null ? new OpenDocument(document, editor.isDirty()) : null;
    }

    /**
     * Applies the edit through a connected text file buffer, and commits it to disk.
     *
     * <p>Refuses to touch a buffer that is already dirty - i.e. something else holds unsaved changes for
     * this file. Committing then would silently write those foreign changes to disk on the user's behalf,
     * and the line number handed to this class comes from the last analysis of the <em>saved</em> file, so
     * it need not point at the same code in the modified buffer.
     *
     * @param file the file to edit, not {@code null}
     * @param line the 1-based line recorded for the issue
     * @param ruleKey the rule key to suppress, not {@code null}
     * @param lineAnchor the anchor recorded for the flagged line, not {@code null}
     * @return what the attempt did, and the line it edited (see {@link SuppressionResult}), never
     *     {@code null}
     * @throws CoreException when connecting to, or committing, the file buffer fails
     * @throws BadLocationException when {@code line} is out of the document's range
     */
    private static SuppressionResult applyToFileBuffer(IFile file, int line, String ruleKey, String lineAnchor)
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
                return SuppressionResult.refused(SuppressionOutcome.NO_BUFFER);
            }
            if (buffer.isDirty())
            {
                return SuppressionResult.refused(SuppressionOutcome.UNSAVED_CHANGES);
            }
            SuppressionResult result = BslSuppression.insert(buffer.getDocument(), line, ruleKey, lineAnchor);
            if (result.inserted())
            {
                buffer.commit(monitor, false);
            }
            return result;
        }
        finally
        {
            manager.disconnect(file.getFullPath(), LocationKind.IFILE, monitor);
        }
    }

    /**
     * Logs a refused suppression. The user-facing half of the report is the caller's job (the issues view
     * puts it on its status line, the quick fix shows it), because only the caller knows whether it was
     * driven by an explicit click; this path can also run unattended, where this project's rules forbid
     * blocking on a modal dialog.
     *
     * @param file the file that was left untouched, not {@code null}
     * @param ruleKey the rule that was not suppressed, not {@code null}
     * @param outcome the reason nothing was written, not {@code null}
     */
    private static void logRefusal(IFile file, String ruleKey, SuppressionOutcome outcome)
    {
        String message = "Not suppressing " + ruleKey //$NON-NLS-1$
            + " in " + file.getFullPath() //$NON-NLS-1$
            + ": " + outcome; //$NON-NLS-1$
        Platform.getLog(SuppressionApplier.class).warn(message);
    }

    /**
     * The document of an open text editor, and whether that editor holds unsaved changes.
     *
     * @param document the editor's document, not {@code null}
     * @param dirty whether the editor is dirty
     */
    private record OpenDocument(IDocument document, boolean dirty)
    {
    }
}
