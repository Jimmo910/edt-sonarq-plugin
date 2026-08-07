/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.ui.views;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.Adapters;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.Path;
import org.eclipse.core.runtime.Platform;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.ide.IDE;
import org.eclipse.ui.texteditor.ITextEditor;

import ru.jimmo.edt.sonarq.ui.resources.WorkspaceFiles;

/** Opens the module editor at the issue line. */
public final class IssueNavigation
{
    private IssueNavigation()
    {
    }

    /** What {@link IssueNavigation#open} did, so the caller can tell the user why nothing opened. */
    public enum OpenOutcome
    {
        /** The editor was opened (and the issue line revealed, when the editor is a text editor). */
        OPENED,

        /** The issue maps to no file in the project, or to a path that does not exist on disk. */
        FILE_UNAVAILABLE,

        /** The file exists but the editor refused to open; the failure has been logged. */
        EDITOR_FAILED
    }

    /**
     * Opens the file of the issue in an editor and reveals the issue line.
     *
     * <p>A file that exists on disk but is not in the resource tree yet - written behind the workspace's
     * back, e.g. by a git checkout run outside EDT - is refreshed into it first (see
     * {@link WorkspaceFiles#existsAfterRefresh}) rather than reported as missing. That reconciliation used to
     * happen only as a side effect of marker synchronization, so with the <em>Show issues in the standard
     * Problems view</em> preference off, double-clicking such an issue did nothing at all (review minor M11).
     *
     * @param page the active workbench page, not {@code null}
     * @param project the project the issue belongs to, not {@code null}
     * @param entry the issue entry, not {@code null}
     * @return what happened, never {@code null}
     */
    public static OpenOutcome open(IWorkbenchPage page, IProject project, IssueEntry entry)
    {
        if (entry.relativePath() == null)
        {
            return OpenOutcome.FILE_UNAVAILABLE;
        }
        IFile file = project.getFile(new Path(entry.relativePath()));
        try
        {
            if (!WorkspaceFiles.existsAfterRefresh(file))
            {
                return OpenOutcome.FILE_UNAVAILABLE;
            }
            IEditorPart editor = IDE.openEditor(page, file, true);
            revealLine(editor, entry.issue().line());
            return OpenOutcome.OPENED;
        }
        catch (PartInitException e)
        {
            Platform.getLog(IssueNavigation.class).warn(e.getMessage(), e);
            return OpenOutcome.EDITOR_FAILED;
        }
        catch (CoreException e)
        {
            // The refresh failed (the project closed underneath us, the path is not accessible): the file
            // cannot be shown, which is what the caller reports - the cause goes to the log.
            Platform.getLog(IssueNavigation.class).warn(e.getMessage(), e);
            return OpenOutcome.FILE_UNAVAILABLE;
        }
    }

    private static void revealLine(IEditorPart editor, int line)
    {
        ITextEditor textEditor = Adapters.adapt(editor, ITextEditor.class);
        if (textEditor == null || line <= 0)
        {
            return;
        }
        IDocument document = textEditor.getDocumentProvider().getDocument(textEditor.getEditorInput());
        if (document == null)
        {
            return;
        }
        try
        {
            int offset = document.getLineOffset(line - 1);
            int length = Math.max(0, document.getLineLength(line - 1) - 1);
            textEditor.selectAndReveal(offset, length);
        }
        catch (BadLocationException e)
        {
            textEditor.selectAndReveal(0, 0);
        }
    }
}
