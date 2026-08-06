/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.ui.views;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.EnumSet;

import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jface.resource.ImageDescriptor;
import org.eclipse.ui.plugin.AbstractUIPlugin;
import org.junit.Test;

import ru.jimmo.edt.sonarq.ui.SonarqPlugin;

/**
 * Tests for {@link SonarIssuesView}'s pure (SWT-free) helpers: the status-line headline extraction and the
 * per-grouping column-hiding decision.
 */
public class SonarIssuesViewTest
{
    /**
     * Regression test for issue #4 point 3: a multi-line error message (e.g. {@code ProcessAnalyzeRunner}'s
     * "<reason>\nFull log: <path>\n<tail>") must show only its first line as the status headline, with the
     * full message reserved for the tooltip and the on-demand Details dialog.
     */
    @Test
    public void headlineOfReturnsOnlyTheFirstLineOfAMultilineMessage()
    {
        String message = "BSL Language Server ran out of memory. Increase 'BSL LS max heap'." + System.lineSeparator()
            + "Full log: C:/state/bsl-report/analyze.log" + System.lineSeparator() + "...tail of the log...";

        assertEquals("BSL Language Server ran out of memory. Increase 'BSL LS max heap'.",
            SonarIssuesView.headlineOf(message));
    }

    @Test
    public void headlineOfReturnsTheWholeMessageWhenItHasNoLineBreak()
    {
        assertEquals("boom", SonarIssuesView.headlineOf("boom"));
    }

    @Test
    public void headlineOfReturnsEmptyStringForAnEmptyMessage()
    {
        assertEquals("", SonarIssuesView.headlineOf(""));
    }

    /**
     * Regression test for issue #4 point 7: the toolbar actions now show an icon instead of text, resolved
     * through {@link AbstractUIPlugin#imageDescriptorFromPlugin} from a bundle-relative {@code icons/*.png}
     * path (see {@code SonarIssuesView#applyToolbarIcon}). A typo in one of those path strings would only
     * surface at runtime as a blank toolbar button, so this checks every path resolves to an actual bundle
     * resource rather than {@code null}.
     */
    @Test
    public void toolbarIconPathsResolveToBundleResources()
    {
        String[] iconPaths = { "icons/refresh.png", "icons/run.png", "icons/project.png", "icons/severity.png",
            "icons/type.png", "icons/groupfile.png", "icons/grouprule.png" };
        for (String iconPath : iconPaths)
        {
            ImageDescriptor descriptor = AbstractUIPlugin.imageDescriptorFromPlugin(SonarqPlugin.PLUGIN_ID, iconPath);
            assertNotNull("expected " + iconPath + " to resolve to a bundle resource", descriptor);
        }
    }

    /**
     * Regression test for issue #3: grouping by Rule repeats the same rule key on every row of the Rule
     * column, so that column should auto-hide while grouped by Rule.
     */
    @Test
    public void hiddenColumnForGroupByRuleIsTheRuleColumn()
    {
        assertEquals(EnumSet.of(SonarIssuesView.IssueColumn.RULE),
            SonarIssuesView.hiddenColumnFor(IssueGrouping.BY_RULE));
    }

    /**
     * Issue #4: grouping by Severity now nests rule groups under each severity, so both the Severity column
     * (repeated by the super-group) and the Rule column (repeated by the rule sub-group) are redundant and
     * should auto-hide while grouped by Severity.
     */
    @Test
    public void hiddenColumnForGroupBySeverityIsSeverityAndRuleColumns()
    {
        assertEquals(EnumSet.of(SonarIssuesView.IssueColumn.SEVERITY, SonarIssuesView.IssueColumn.RULE),
            SonarIssuesView.hiddenColumnFor(IssueGrouping.BY_SEVERITY));
    }

    /**
     * Regression test for issue #3: grouping by File shows the line number per row in the Location column,
     * which is useful information, so no column should auto-hide while grouped by File.
     */
    @Test
    public void hiddenColumnForGroupByFileIsEmpty()
    {
        assertEquals(EnumSet.noneOf(SonarIssuesView.IssueColumn.class),
            SonarIssuesView.hiddenColumnFor(IssueGrouping.BY_FILE));
    }

    /**
     * A project with the conventional {@code src} folder is recognized as a 1C project, so the view prefers
     * it over whatever project happens to sort first in the workspace.
     */
    @Test
    public void projectWithSourceFolderIsRecognizedAsOneCProject() throws CoreException
    {
        IProject project = createProject("sonarq-view-src-project", true);
        try
        {
            assertTrue(SonarIssuesView.isOneCProject(project));
        }
        finally
        {
            project.delete(true, true, new NullProgressMonitor());
        }
    }

    /** A project with neither an EDT nature nor a {@code src} folder is not preferred. */
    @Test
    public void projectWithoutNatureOrSourceFolderIsNotAOneCProject() throws CoreException
    {
        IProject project = createProject("sonarq-view-plain-project", false);
        try
        {
            assertFalse(SonarIssuesView.isOneCProject(project));
        }
        finally
        {
            project.delete(true, true, new NullProgressMonitor());
        }
    }

    private static IProject createProject(String name, boolean withSourceFolder) throws CoreException
    {
        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(name);
        if (!project.exists())
        {
            project.create(new NullProgressMonitor());
        }
        project.open(new NullProgressMonitor());
        if (withSourceFolder)
        {
            IFolder folder = project.getFolder("src");
            if (!folder.exists())
            {
                folder.create(true, true, new NullProgressMonitor());
            }
        }
        return project;
    }
}
