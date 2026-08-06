/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.ui.suppress;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.Platform;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.osgi.util.NLS;
import org.eclipse.swt.graphics.Image;
import org.eclipse.ui.IMarkerResolution2;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.PlatformUI;

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
            // The edit already removed the cause of this finding; delete this one marker right away so the
            // Problems view reflects it immediately instead of waiting for the next full issue-tree refresh
            // to re-sync all markers (see ru.jimmo.edt.sonarq.ui.markers.IssueMarkerSynchronizer#sync).
            marker.delete();
            notifyIssuesView(page, issueKey);
        }
        catch (CoreException | BadLocationException e)
        {
            Platform.getLog(getClass()).error(e.getMessage(), e);
        }
    }

    /**
     * Lets the SonarQube Issues view renumber its in-memory issues for the two comment lines just inserted,
     * exactly as its own context-menu suppression does. Without this the view would keep pre-edit line
     * numbers for the rest of the file and the next suppression from there would wrap the wrong lines.
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
