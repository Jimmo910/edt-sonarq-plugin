/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.ui.suppress;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.junit.Test;

import ru.jimmo.edt.sonarq.core.model.IssueQuery;
import ru.jimmo.edt.sonarq.core.model.IssueSnapshot;
import ru.jimmo.edt.sonarq.core.model.SonarIssue;
import ru.jimmo.edt.sonarq.core.model.SonarIssueType;
import ru.jimmo.edt.sonarq.core.model.SonarSeverity;
import ru.jimmo.edt.sonarq.core.suppress.SuppressionLineShift;

/**
 * Tests the identity that travels from a Problems-view quick fix to the SonarQube Issues view.
 *
 * <p>The keys of local-analysis issues are built from rule, file URI, line and column and carry no project,
 * so two projects holding the same relative module path produce equal keys for different findings. The view
 * used to renumber its snapshot on the key alone: a quick fix in project A then shifted the line numbers of
 * project B's issues, in a file no edit had touched, and the next suppression from the view wrapped the wrong
 * lines.
 */
public class SuppressedIssueTest
{
    private static final String COMPONENT = "proj:src/CommonModules/Common/Module.bsl";

    private static final String ISSUE_KEY = "bsl:MethodSize|" + COMPONENT + "|10|1";

    /** The two projects share a module path, and therefore an issue key. */
    private final IProject projectA = ResourcesPlugin.getWorkspace().getRoot().getProject("suppressed-issue-a");

    private final IProject projectB = ResourcesPlugin.getWorkspace().getRoot().getProject("suppressed-issue-b");

    /** The defect: the view is showing project B while the quick fix edited a file of project A. */
    @Test
    public void aQuickFixInAnotherProjectDoesNotTouchTheViewsSnapshot()
    {
        IssueSnapshot shown = snapshot();

        IssueSnapshot after = afterQuickFix(new SuppressedIssue(projectA, ISSUE_KEY), projectB, shown);

        assertSame("project B's snapshot must be left exactly as it was", shown, after);
        assertEquals(10, lineOf(after, ISSUE_KEY));
        assertEquals(20, lineOf(after, "below"));
    }

    /** The other half: a quick fix in the very project on screen still updates it. */
    @Test
    public void aQuickFixInTheProjectOnScreenStillUpdatesTheSnapshot()
    {
        IssueSnapshot shown = snapshot();

        IssueSnapshot after = afterQuickFix(new SuppressedIssue(projectA, ISSUE_KEY), projectA, shown);

        assertFalse("the suppressed issue is gone", after.issues().stream()
            .anyMatch(issue -> issue.key().equals(ISSUE_KEY)));
        assertEquals("the issue below it moved down by the two comment lines", 22, lineOf(after, "below"));
    }

    /** A notification for the project on screen resolves to the issue the view has to renumber around. */
    @Test
    public void locateInFindsTheIssueOfTheProjectOnScreen()
    {
        Optional<SonarIssue> located = new SuppressedIssue(projectA, ISSUE_KEY).locateIn(projectA, snapshot());

        assertTrue(located.isPresent());
        assertEquals(ISSUE_KEY, located.get().key());
    }

    /** The same notification resolves to nothing when the view is bound to another project. */
    @Test
    public void locateInIgnoresTheSameKeyInAnotherProject()
    {
        assertTrue(new SuppressedIssue(projectA, ISSUE_KEY).locateIn(projectB, snapshot()).isEmpty());
    }

    /** A view bound to no project at all, or holding no snapshot, is left alone. */
    @Test
    public void locateInHandlesAViewWithNoProjectOrNoSnapshot()
    {
        assertTrue(new SuppressedIssue(projectA, ISSUE_KEY).locateIn(null, snapshot()).isEmpty());
        assertTrue(new SuppressedIssue(projectA, ISSUE_KEY).locateIn(projectA, null).isEmpty());
    }

    /** A marker without an issue key carries no usable identity. */
    @Test
    public void locateInIgnoresAnEmptyIssueKey()
    {
        assertTrue(new SuppressedIssue(projectA, "").locateIn(projectA, snapshot()).isEmpty());
    }

    /**
     * The view's hop, exactly as {@code SonarIssuesView#issueSuppressedExternally} performs it: locate the
     * issue, and renumber the snapshot around it when - and only when - one was found.
     *
     * @param suppressed the identity the quick fix sent
     * @param viewProject the project the view is showing
     * @param shown the view's snapshot
     * @return the snapshot the view would hold afterwards
     */
    private static IssueSnapshot afterQuickFix(SuppressedIssue suppressed, IProject viewProject,
        IssueSnapshot shown)
    {
        return suppressed.locateIn(viewProject, shown)
            .map(issue -> SuppressionLineShift.applyAfterSuppress(shown, issue))
            .orElse(shown);
    }

    private static IssueSnapshot snapshot()
    {
        return new IssueSnapshot(new IssueQuery("proj", null),
            List.of(issue(ISSUE_KEY, 10), issue("below", 20)), 2, Instant.EPOCH);
    }

    private static SonarIssue issue(String key, int line)
    {
        return new SonarIssue(key, "bsl:MethodSize", SonarSeverity.MAJOR, SonarIssueType.CODE_SMELL, COMPONENT,
            "message", line);
    }

    private static int lineOf(IssueSnapshot snapshot, String key)
    {
        return snapshot.issues().stream().filter(issue -> issue.key().equals(key)).findFirst().orElseThrow()
            .line();
    }
}
