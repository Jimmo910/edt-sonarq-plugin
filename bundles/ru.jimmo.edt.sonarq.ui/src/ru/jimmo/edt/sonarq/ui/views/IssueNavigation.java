/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.ui.views;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.Adapters;
import org.eclipse.core.runtime.Path;
import org.eclipse.core.runtime.Platform;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.ide.IDE;
import org.eclipse.ui.texteditor.ITextEditor;

/** Opens the module editor at the issue line. */
public final class IssueNavigation
{
    private IssueNavigation()
    {
    }

    /**
     * Opens the file of the issue in an editor and reveals the issue line.
     *
     * @param page the active workbench page, not {@code null}
     * @param project the project the issue belongs to, not {@code null}
     * @param entry the issue entry, not {@code null}
     * @return {@code true} when the editor was opened
     */
    public static boolean open(IWorkbenchPage page, IProject project, IssueEntry entry)
    {
        if (entry.relativePath() == null)
        {
            return false;
        }
        IFile file = project.getFile(new Path(entry.relativePath()));
        if (!file.exists())
        {
            return false;
        }
        try
        {
            IEditorPart editor = IDE.openEditor(page, file, true);
            revealLine(editor, entry.issue().line());
            return true;
        }
        catch (PartInitException e)
        {
            Platform.getLog(IssueNavigation.class).warn(e.getMessage(), e);
            return false;
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
