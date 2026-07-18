/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.ui.views;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.util.Optional;

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
        assertEquals(Optional.of(SonarIssuesView.IssueColumn.RULE),
            SonarIssuesView.hiddenColumnFor(IssueGrouping.BY_RULE));
    }

    /**
     * Regression test for issue #3: grouping by Severity repeats the same severity on every row of the
     * Severity column, so that column should auto-hide while grouped by Severity.
     */
    @Test
    public void hiddenColumnForGroupBySeverityIsTheSeverityColumn()
    {
        assertEquals(Optional.of(SonarIssuesView.IssueColumn.SEVERITY),
            SonarIssuesView.hiddenColumnFor(IssueGrouping.BY_SEVERITY));
    }

    /**
     * Regression test for issue #3: grouping by File shows the line number per row in the Location column,
     * which is useful information, so no column should auto-hide while grouped by File.
     */
    @Test
    public void hiddenColumnForGroupByFileIsEmpty()
    {
        assertEquals(Optional.empty(), SonarIssuesView.hiddenColumnFor(IssueGrouping.BY_FILE));
    }
}
