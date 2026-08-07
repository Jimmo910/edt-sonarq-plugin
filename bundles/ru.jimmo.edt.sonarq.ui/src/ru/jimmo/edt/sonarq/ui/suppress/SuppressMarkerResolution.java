/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.ui.suppress;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.IWorkspaceRunnable;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.Platform;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.osgi.util.NLS;
import org.eclipse.swt.graphics.Image;
import org.eclipse.ui.IMarkerResolution2;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.PlatformUI;

import ru.jimmo.edt.sonarq.core.suppress.SuppressionLineShift;
import ru.jimmo.edt.sonarq.ui.Messages;
import ru.jimmo.edt.sonarq.ui.markers.IssueMarkers;
import ru.jimmo.edt.sonarq.ui.views.SonarIssuesView;

/**
 * The single "Suppress" quick fix {@link SuppressMarkerResolutionGenerator} offers for one issue marker
 * (issue #7).
 */
final class SuppressMarkerResolution implements IMarkerResolution2
{
    private final String bareRuleKey;

    /**
     * @param bareRuleKey the marker's rule key with any {@code bsl:} prefix already stripped, not
     *     {@code null}
     */
    SuppressMarkerResolution(String bareRuleKey)
    {
        this.bareRuleKey = bareRuleKey;
    }

    @Override
    public String getLabel()
    {
        return NLS.bind(Messages.Suppress_ResolutionLabel, bareRuleKey);
    }

    @Override
    public String getDescription()
    {
        return getLabel();
    }

    @Override
    public Image getImage()
    {
        return null;
    }

    @Override
    public void run(IMarker marker)
    {
        IResource resource = marker.getResource();
        int line = marker.getAttribute(IMarker.LINE_NUMBER, -1);
        if (!(resource instanceof IFile file) || line <= 0)
        {
            return;
        }
        try
        {
            String issueKey = marker.getAttribute(IssueMarkers.ATTR_ISSUE_KEY, ""); //$NON-NLS-1$
            IWorkbenchPage page = activePage();
            if (!SuppressionApplier.apply(file, line, bareRuleKey, page))
            {
                // Nothing was written (an already-suppressed line, or a file with unsaved changes) - leave
                // both the marker and the issue view's line numbers exactly as they are.
                return;
            }
            // The edit already removed the cause of this finding; drop this one marker and renumber the rest
            // of the file's markers right away, instead of waiting for the next full issue-tree refresh to
            // re-sync them all (see ru.jimmo.edt.sonarq.ui.markers.IssueMarkerSynchronizer#sync).
            renumberFileMarkers(file, marker, line);
            notifyIssuesView(page, issueKey);
        }
        catch (CoreException | BadLocationException e)
        {
            Platform.getLog(getClass()).error(e.getMessage(), e);
        }
    }

    /**
     * Deletes the resolved marker and renumbers the file's remaining issue markers for the two comment lines
     * just inserted, with the same arithmetic {@link SuppressionLineShift} applies to the issue view's
     * snapshot.
     *
     * <p>This is what makes the quick fix correct on its own, without a SonarQube Issues view: markers
     * outlive that view (they are created by {@code MarkerSyncJob}, survive its closing, and the background
     * auto-sync creates them for every configured project while the view tracks only one), so relying on
     * {@link #notifyIssuesView} alone left every other marker of the same file carrying its pre-edit line
     * number whenever the view was closed or showing another project. The next quick fix in that file then
     * wrapped a line two lines above the flagged one - committed straight to disk, with no undo, and with
     * neither of {@code BslSuppression#insert}'s guards firing, because the line it lands on is ordinary
     * code.
     *
     * <p>Both steps run in a single {@link IWorkspaceRunnable} on the file's rule, as all other marker
     * mutation in this plug-in does (see
     * {@code ru.jimmo.edt.sonarq.ui.markers.IssueMarkerSynchronizer#sync}), so a concurrent marker sync
     * cannot interleave with a half-renumbered file.
     *
     * @param file the edited file, not {@code null}
     * @param resolved the marker whose quick fix was just applied, not {@code null}; deleted by this call
     * @param codeLine the 1-based line the {@code -off}/{@code -on} comments were wrapped around
     * @throws CoreException when the workspace operation fails
     */
    private static void renumberFileMarkers(IFile file, IMarker resolved, int codeLine) throws CoreException
    {
        long resolvedId = resolved.getId();
        IWorkspaceRunnable runnable = monitor ->
        {
            for (IMarker sibling : file.findMarkers(IssueMarkers.MARKER_TYPE, true, IResource.DEPTH_ZERO))
            {
                if (sibling.getId() == resolvedId)
                {
                    continue;
                }
                int line = sibling.getAttribute(IMarker.LINE_NUMBER, -1);
                if (line <= 0)
                {
                    // A file-level marker carries no line to shift.
                    continue;
                }
                int shifted = SuppressionLineShift.shiftedLine(line, codeLine);
                if (shifted != line)
                {
                    sibling.setAttribute(IMarker.LINE_NUMBER, shifted);
                }
            }
            // Last, so the loop above can still recognize it by id; a no-op if it is already gone.
            resolved.delete();
        };
        file.getWorkspace().run(runnable, file, IWorkspace.AVOID_UPDATE, null);
    }

    /**
     * Lets the SonarQube Issues view renumber its in-memory issues for the two comment lines just inserted,
     * exactly as its own context-menu suppression does. Without this the view would keep pre-edit line
     * numbers for the rest of the file and the next suppression from its tree would wrap the wrong lines.
     * This only covers the view's own snapshot - the markers are renumbered by {@link #renumberFileMarkers},
     * which needs no view at all.
     *
     * @param page the workbench page to look the view up in, or {@code null} when there is none
     * @param issueKey the suppressed issue's key as carried by the marker, never {@code null} (empty when
     *     the marker has no such attribute)
     */
    private static void notifyIssuesView(IWorkbenchPage page, String issueKey)
    {
        if (page == null || issueKey.isEmpty())
        {
            return;
        }
        // findView returns null when the view is not open at all - then there is no model to update.
        if (page.findView(SonarIssuesView.VIEW_ID) instanceof SonarIssuesView view)
        {
            view.issueSuppressedExternally(issueKey);
        }
    }

    private static IWorkbenchPage activePage()
    {
        if (!PlatformUI.isWorkbenchRunning() || PlatformUI.getWorkbench().getActiveWorkbenchWindow() == null)
        {
            return null;
        }
        return PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage();
    }
}
