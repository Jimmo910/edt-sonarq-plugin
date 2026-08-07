/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.ui.suppress;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import ru.jimmo.edt.sonarq.core.model.SonarIssue;
import ru.jimmo.edt.sonarq.core.model.SonarIssueType;
import ru.jimmo.edt.sonarq.core.model.SonarSeverity;
import ru.jimmo.edt.sonarq.core.suppress.LineAnchor;
import ru.jimmo.edt.sonarq.ui.markers.IssueMarkers;
import ru.jimmo.edt.sonarq.ui.markers.MarkerSyncJob;
import ru.jimmo.edt.sonarq.ui.views.IssueEntry;

/**
 * Tests the Problems-view quick fix on its own, the way it runs when the SonarQube Issues view is closed or
 * bound to another project: headless, with no workbench at all, so {@code SuppressMarkerResolution} takes the
 * file-buffer path and its view notification is a no-op.
 *
 * <p>That is the configuration the markers have to survive by themselves. They outlive the view - the
 * background auto-sync creates them for every configured project, and closing the view does not delete them -
 * so before the renumbering was done on the markers themselves, a second quick fix in the same file wrapped
 * the code two lines above the flagged one and committed it straight to disk, unguarded and with no undo.
 */
public class SuppressMarkerResolutionTest
{
    private static final String RELATIVE_PATH = "src/Module.bsl";
    private static final int LINES = 25;

    private IProject project;
    private IFile file;

    @Before
    public void setUp() throws CoreException
    {
        project = ResourcesPlugin.getWorkspace().getRoot().getProject("suppress-quickfix-test");
        if (!project.exists())
        {
            project.create(new NullProgressMonitor());
        }
        project.open(new NullProgressMonitor());
        IFolder folder = project.getFolder("src");
        if (!folder.exists())
        {
            folder.create(true, true, new NullProgressMonitor());
        }
        file = project.getFile(RELATIVE_PATH);
        if (file.exists())
        {
            file.delete(true, new NullProgressMonitor());
        }
        file.create(new ByteArrayInputStream(source().getBytes(StandardCharsets.UTF_8)), true,
            new NullProgressMonitor());
        file.setCharset(StandardCharsets.UTF_8.name(), new NullProgressMonitor());
    }

    @After
    public void tearDown() throws CoreException
    {
        project.delete(true, true, new NullProgressMonitor());
    }

    /**
     * The resolved marker goes away, the marker above the suppression keeps its line, and the marker below
     * it moves down by the two inserted comment lines - all without a view to tell.
     */
    @Test
    public void renumbersTheOtherMarkersOfTheFileWithoutAnyView() throws Exception
    {
        IMarker above = marker("R0", 5);
        IMarker resolved = marker("R1", 10);
        IMarker below = marker("R2", 20);

        new SuppressMarkerResolution("R1").run(resolved);

        assertFalse("the resolved marker must be gone", resolved.exists());
        assertEquals(5, above.getAttribute(IMarker.LINE_NUMBER, -1));
        assertEquals(22, below.getAttribute(IMarker.LINE_NUMBER, -1));
        assertEquals(expected(Map.of(10, "R1")), onDisk());
    }

    /**
     * The consequence that matters: the next quick fix in the same file still wraps its own statement. With
     * the stale line number it wrapped {@code L18;} - two lines above the flagged one - and neither of
     * {@code BslSuppression#insert}'s guards fires on that, because it is ordinary code.
     */
    @Test
    public void aSecondQuickFixInTheSameFileWrapsItsOwnLine() throws Exception
    {
        IMarker resolved = marker("R1", 10);
        IMarker below = marker("R2", 20);

        new SuppressMarkerResolution("R1").run(resolved);
        new SuppressMarkerResolution("R2").run(below);

        assertFalse(below.exists());
        assertEquals(expected(Map.of(10, "R1", 20, "R2")), onDisk());
    }

    /** A file-level marker carries no line number to shift, and must not gain one. */
    @Test
    public void markersWithoutALineNumberAreLeftAlone() throws Exception
    {
        IMarker fileLevel = file.createMarker(IssueMarkers.MARKER_TYPE);
        fileLevel.setAttribute(IssueMarkers.ATTR_RULE_KEY, "R3");
        IMarker resolved = marker("R1", 10);

        new SuppressMarkerResolution("R1").run(resolved);

        assertTrue(fileLevel.exists());
        assertEquals(-1, fileLevel.getAttribute(IMarker.LINE_NUMBER, -1));
    }

    /**
     * A refused edit - here a line that is already wrapped in the very suppression being applied - writes
     * nothing, so nothing may be renumbered or deleted either.
     */
    @Test
    public void aNoOpEditLeavesTheMarkersUntouched() throws Exception
    {
        IMarker resolved = marker("R1", 10);
        IMarker below = marker("R2", 20);
        new SuppressMarkerResolution("R1").run(resolved);
        String afterFirst = onDisk();

        IMarker again = marker("R1", 11);
        new SuppressMarkerResolution("R1").run(again);

        assertTrue("nothing was written, so nothing was resolved", again.exists());
        assertEquals(11, again.getAttribute(IMarker.LINE_NUMBER, -1));
        assertEquals(22, below.getAttribute(IMarker.LINE_NUMBER, -1));
        assertEquals(afterFirst, onDisk());
    }

    /**
     * The whole path, as it runs in production: the marker synchronization fingerprints the flagged lines
     * and stores the fingerprints on the markers, and the quick fix verifies its line against them.
     *
     * <p>The scenario is the one no amount of renumbering could fix. One suppression grows the file by two
     * lines; a server-mode refresh then re-synchronizes the markers from the line numbers SonarQube recorded
     * at its last analysis, which know nothing about that edit - so the second marker is back on its pre-edit
     * line 20, two lines above its own statement. The carried anchor is what makes the second quick fix wrap
     * {@code L20;} anyway, instead of {@code L18;}.
     */
    @Test
    public void quickFixFollowsTheAnchorTheMarkerSyncStoredAfterARefreshRestoredPreEditLines() throws Exception
    {
        syncMarkers(issue("k1", "R1", 10), issue("k2", "R2", 20));

        new SuppressMarkerResolution("R1").run(markerOf("k1"));
        // The refresh: the same pre-edit line numbers, re-synchronized onto the already-edited file.
        syncMarkers(issue("k1", "R1", 10), issue("k2", "R2", 20));
        assertEquals("the refresh restored the pre-edit line number", 20,
            markerOf("k2").getAttribute(IMarker.LINE_NUMBER, -1));

        new SuppressMarkerResolution("R2").run(markerOf("k2"));

        assertEquals(expected(Map.of(10, "R1", 20, "R2")), onDisk());
    }

    /** The marker sync stores the anchor of the line the issue was reported on. */
    @Test
    public void markerSyncStoresTheAnchorOfTheFlaggedLine() throws Exception
    {
        syncMarkers(issue("k1", "R1", 10));

        assertEquals(LineAnchor.of("L10;"), markerOf("k1").getAttribute(IssueMarkers.ATTR_LINE_ANCHOR, ""));
    }

    /**
     * A marker whose anchored line is gone - the user rewrote it since the analysis - is not resolved at all:
     * no byte of the file changes, and the marker survives so the finding is not silently lost.
     */
    @Test
    public void quickFixWritesNothingWhenTheMarkersAnchorIsNoLongerInTheFile() throws Exception
    {
        String untouched = onDisk();
        IMarker stale = marker("R1", 10);
        stale.setAttribute(IssueMarkers.ATTR_LINE_ANCHOR, LineAnchor.of("this line is long gone;"));

        new SuppressMarkerResolution("R1").run(stale);

        assertTrue("a refused quick fix must not resolve the marker", stale.exists());
        assertEquals(untouched, onDisk());
    }

    /**
     * Runs a marker synchronization exactly as the issues view and the background auto-sync do, and waits
     * for it: the job is what fingerprints the lines.
     *
     * @param issues the issues to materialize as markers
     * @throws InterruptedException when the wait is interrupted
     */
    private void syncMarkers(SonarIssue... issues) throws InterruptedException
    {
        List<IssueEntry> entries = List.of(issues).stream()
            .map(issue -> new IssueEntry(issue, RELATIVE_PATH))
            .toList();
        MarkerSyncJob job = new MarkerSyncJob(project, () -> entries);
        job.schedule();
        job.join();
    }

    /**
     * The single marker carrying the given issue key.
     *
     * @param issueKey the issue key to look for
     * @return the marker
     * @throws CoreException when the markers cannot be read
     */
    private IMarker markerOf(String issueKey) throws CoreException
    {
        for (IMarker marker : file.findMarkers(IssueMarkers.MARKER_TYPE, true, IResource.DEPTH_ZERO))
        {
            if (issueKey.equals(marker.getAttribute(IssueMarkers.ATTR_ISSUE_KEY, "")))
            {
                return marker;
            }
        }
        throw new AssertionError("no marker for issue " + issueKey);
    }

    private static SonarIssue issue(String key, String ruleKey, int line)
    {
        return new SonarIssue(key, ruleKey, SonarSeverity.MAJOR, SonarIssueType.CODE_SMELL,
            "proj:" + RELATIVE_PATH, "boom", line);
    }

    private IMarker marker(String ruleKey, int line) throws CoreException
    {
        IMarker marker = file.createMarker(IssueMarkers.MARKER_TYPE);
        marker.setAttribute(IMarker.LINE_NUMBER, line);
        marker.setAttribute(IssueMarkers.ATTR_RULE_KEY, ruleKey);
        marker.setAttribute(IMarker.SEVERITY, IMarker.SEVERITY_WARNING);
        return marker;
    }

    private String onDisk() throws IOException
    {
        return Files.readString(file.getLocation().toFile().toPath(), StandardCharsets.UTF_8);
    }

    /**
     * The unedited module: {@code LINES} statements, each naming its own 1-based line number, so a wrongly
     * wrapped line is visible in the assertion failure instead of having to be counted.
     *
     * @return the initial file content
     */
    private static String source()
    {
        StringBuilder text = new StringBuilder();
        for (int line = 1; line <= LINES; line++)
        {
            text.append("L").append(line).append(";\n");
        }
        return text.toString();
    }

    /**
     * The expected content after wrapping the given original lines, each with its own rule.
     *
     * @param suppressions the rule key to wrap, by original 1-based line number
     * @return the expected file content
     */
    private static String expected(Map<Integer, String> suppressions)
    {
        StringBuilder text = new StringBuilder();
        for (int line = 1; line <= LINES; line++)
        {
            String ruleKey = suppressions.get(line);
            if (ruleKey != null)
            {
                text.append("// BSLLS:").append(ruleKey).append("-off\n");
            }
            text.append("L").append(line).append(";\n");
            if (ruleKey != null)
            {
                text.append("// BSLLS:").append(ruleKey).append("-on\n");
            }
        }
        return text.toString();
    }
}
