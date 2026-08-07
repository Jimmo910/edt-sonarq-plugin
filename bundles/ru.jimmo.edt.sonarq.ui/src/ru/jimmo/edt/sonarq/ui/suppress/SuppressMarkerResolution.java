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
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.osgi.util.NLS;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IMarkerResolution2;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.PlatformUI;

import ru.jimmo.edt.sonarq.core.suppress.SuppressionLineShift;
import ru.jimmo.edt.sonarq.core.suppress.SuppressionOutcome;
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

    private final MarkerRenumbering renumbering;

    private final UserNotice notice;

    /**
     * @param bareRuleKey the marker's rule key with any {@code bsl:} prefix already stripped, not
     *     {@code null}
     */
    SuppressMarkerResolution(String bareRuleKey)
    {
        this(bareRuleKey, SuppressMarkerResolution::renumberFileMarkers, SuppressMarkerResolution::openDialog);
    }

    /**
     * The seams of the two steps that follow a successful edit, so both can be driven headless: the marker
     * bookkeeping (whose failure is the case worth testing) and the channel its failure is reported through
     * (which otherwise needs a running workbench).
     *
     * @param bareRuleKey the marker's rule key with any {@code bsl:} prefix already stripped, not
     *     {@code null}
     * @param renumbering the marker bookkeeping to run after a successful edit, not {@code null}
     * @param notice the channel to tell the user through, not {@code null}
     */
    SuppressMarkerResolution(String bareRuleKey, MarkerRenumbering renumbering, UserNotice notice)
    {
        this.bareRuleKey = bareRuleKey;
        this.renumbering = renumbering;
        this.notice = notice;
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
            SuppressedIssue suppressed = SuppressedIssue.of(marker);
            String lineAnchor = marker.getAttribute(IssueMarkers.ATTR_LINE_ANCHOR, ""); //$NON-NLS-1$
            IWorkbenchPage page = activePage();
            SuppressionOutcome outcome = SuppressionApplier.apply(file, line, bareRuleKey, lineAnchor, page);
            if (!outcome.inserted())
            {
                // Nothing was written (the file changed since the analysis, the line is already suppressed,
                // or the file has unsaved changes) - leave both the marker and the issue view's line numbers
                // exactly as they are, and tell the user why the quick fix appears to have done nothing.
                notice.show(Messages.Suppress_Refused_Title, SuppressionMessages.describe(outcome));
                return;
            }
            updateModelsAfterEdit(file, marker, line, page, suppressed);
        }
        catch (CoreException | BadLocationException e)
        {
            Platform.getLog(getClass()).error(e.getMessage(), e);
        }
    }

    /**
     * Brings the two line-number models that survive this quick fix - the file's other issue markers and the
     * issues view's snapshot - in step with the edit that was just written, and tells the user when that
     * fails.
     *
     * <p>The edit is already on disk by the time this runs and cannot be taken back, so a failure here is not
     * a failure of the suppression: it leaves correct source next to line numbers that are two lines short.
     * The anchors mean the next quick fix will refuse or relocate rather than cut into the wrong statement, so
     * nothing can be corrupted by it - but the numbers on screen are wrong until the issues are refreshed, and
     * a user who is only told through the workspace log is not told at all. Hence the same visible channel the
     * refusals use.
     *
     * @param file the edited file, not {@code null}
     * @param resolved the marker whose quick fix was just applied, not {@code null}
     * @param codeLine the 1-based line the {@code -off}/{@code -on} comments were wrapped around
     * @param page the active workbench page, or {@code null} when there is none
     * @param suppressed the identity of the suppressed issue, or {@code null} when the marker carried none
     */
    private void updateModelsAfterEdit(IFile file, IMarker resolved, int codeLine, IWorkbenchPage page,
        SuppressedIssue suppressed)
    {
        try
        {
            // The edit already removed the cause of this finding; drop this one marker and renumber the rest
            // of the file's markers right away, instead of waiting for the next full issue-tree refresh to
            // re-sync them all (see ru.jimmo.edt.sonarq.ui.markers.IssueMarkerSynchronizer#sync).
            renumbering.renumber(file, resolved, codeLine);
            notifyIssuesView(page, suppressed);
        }
        catch (CoreException | RuntimeException e)
        {
            Platform.getLog(getClass()).error(e.getMessage(), e);
            notice.show(Messages.Suppress_Stale_Title, Messages.Suppress_Stale_Message);
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
     * @param suppressed the project-scoped identity of the suppressed issue, or {@code null} when the marker
     *     is not inside a project
     */
    private static void notifyIssuesView(IWorkbenchPage page, SuppressedIssue suppressed)
    {
        if (page == null || suppressed == null)
        {
            return;
        }
        // findView returns null when the view is not open at all - then there is no model to update.
        if (page.findView(SonarIssuesView.VIEW_ID) instanceof SonarIssuesView view)
        {
            view.issueSuppressedExternally(suppressed);
        }
    }

    /**
     * Tells the user something about a quick fix that is not visible in the Problems view itself: that
     * nothing was written and why, or that the edit landed but the line numbers on screen did not follow.
     *
     * <p>A dialog is the right channel here, and the only one that reaches the user: this method only ever
     * runs from an {@link IMarkerResolution2#run} call, i.e. in direct response to an explicit click in the
     * Problems view, which is exactly the case this project's unattended-safety rule allows a modal window
     * in (the same reason the issues view's "Details" link may open one). It is also the one suppression
     * entry point that cannot fall back to a status line - the issues view need not even be open. Skipped
     * with no workbench, which is how the headless tests drive this class.
     *
     * @param title the dialog title, not {@code null}
     * @param message the message to show, not {@code null}
     */
    private static void openDialog(String title, String message)
    {
        if (!PlatformUI.isWorkbenchRunning() || PlatformUI.getWorkbench().getActiveWorkbenchWindow() == null)
        {
            // The failure is already in the log; there is nobody to show it to.
            return;
        }
        Shell shell = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getShell();
        MessageDialog.openInformation(shell, title, message);
    }

    private static IWorkbenchPage activePage()
    {
        if (!PlatformUI.isWorkbenchRunning() || PlatformUI.getWorkbench().getActiveWorkbenchWindow() == null)
        {
            return null;
        }
        return PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage();
    }

    /** The marker bookkeeping that follows a successful edit; {@link #renumberFileMarkers} in production. */
    @FunctionalInterface
    interface MarkerRenumbering
    {
        /**
         * Deletes the resolved marker and renumbers the file's remaining issue markers.
         *
         * @param file the edited file, not {@code null}
         * @param resolved the marker whose quick fix was applied, not {@code null}
         * @param codeLine the 1-based line the comments were wrapped around
         * @throws CoreException when the workspace operation fails
         */
        void renumber(IFile file, IMarker resolved, int codeLine) throws CoreException;
    }

    /** The channel this quick fix reports through; {@link #openDialog} in production. */
    @FunctionalInterface
    interface UserNotice
    {
        /**
         * Shows one message to the user.
         *
         * @param title the title, not {@code null}
         * @param message the message, not {@code null}
         */
        void show(String title, String message);
    }
}
