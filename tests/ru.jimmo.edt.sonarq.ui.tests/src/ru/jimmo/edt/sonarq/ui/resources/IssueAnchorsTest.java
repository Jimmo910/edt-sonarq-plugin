/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.ui.resources;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jface.text.Document;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import ru.jimmo.edt.sonarq.core.model.IssueQuery;
import ru.jimmo.edt.sonarq.core.model.IssueSnapshot;
import ru.jimmo.edt.sonarq.core.model.SonarIssue;
import ru.jimmo.edt.sonarq.core.model.SonarIssueType;
import ru.jimmo.edt.sonarq.core.model.SonarSeverity;
import ru.jimmo.edt.sonarq.core.suppress.LineAnchor;
import ru.jimmo.edt.sonarq.core.suppress.SuppressionLineShift;
import ru.jimmo.edt.sonarq.core.suppress.SuppressionOutcome;
import ru.jimmo.edt.sonarq.ui.markers.IssueMarkers;
import ru.jimmo.edt.sonarq.ui.suppress.SuppressionApplier;
import ru.jimmo.edt.sonarq.ui.views.IssueEntry;

/** Tests for {@link IssueAnchors}: where a quick-suppress gets the fingerprint it verifies against. */
public class IssueAnchorsTest
{
    private static final String PROJECT_KEY = "proj";
    private static final String RELATIVE_PATH = "src/Module.bsl";
    private static final String SOURCE = "Процедура П()\n    А = 1;\n    Б = 2;\nКонецПроцедуры\n";

    private IProject project;
    private IFile file;

    @Before
    public void setUp() throws CoreException
    {
        project = ResourcesPlugin.getWorkspace().getRoot().getProject("issue-anchors-test");
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
        file.create(new ByteArrayInputStream(SOURCE.getBytes(StandardCharsets.UTF_8)), true,
            new NullProgressMonitor());
        file.setCharset(StandardCharsets.UTF_8.name(), new NullProgressMonitor());
    }

    @After
    public void tearDown() throws CoreException
    {
        project.delete(true, true, new NullProgressMonitor());
    }

    @Test
    public void anchorsEachIssueWithTheTextOfItsReportedLineAndItsContext()
    {
        List<IssueEntry> anchored = IssueAnchors.anchor(project,
            List.of(entry("k1", 2), entry("k2", 3)));

        assertEquals(anchorOfSourceLine(2), anchored.get(0).issue().lineAnchor());
        assertEquals(anchorOfSourceLine(3), anchored.get(1).issue().lineAnchor());
    }

    @Test
    public void leavesTheAnchorEmptyWhenTheFileIsNotInTheProject()
    {
        List<IssueEntry> anchored = IssueAnchors.anchor(project,
            List.of(new IssueEntry(issue("k1", 2), "src/Missing.bsl")));

        assertEquals(LineAnchor.NONE, anchored.get(0).issue().lineAnchor());
    }

    @Test
    public void leavesTheAnchorEmptyForUnmappedAndFileLevelIssues()
    {
        List<IssueEntry> anchored = IssueAnchors.anchor(project,
            List.of(new IssueEntry(issue("k1", 2), null), entry("k2", 0)));

        assertEquals(LineAnchor.NONE, anchored.get(0).issue().lineAnchor());
        assertEquals(LineAnchor.NONE, anchored.get(1).issue().lineAnchor());
    }

    @Test
    public void leavesTheAnchorEmptyWhenTheReportedLineIsPastTheEndOfTheFile()
    {
        List<IssueEntry> anchored = IssueAnchors.anchor(project, List.of(entry("k1", 999)));

        assertEquals(LineAnchor.NONE, anchored.get(0).issue().lineAnchor());
    }

    /** Idempotent: a second pass (the marker sync after the refresh) must not re-read or re-fingerprint. */
    @Test
    public void keepsAnAnchorThatIsAlreadyThereAndReturnsTheSameListWhenThereIsNothingToDo()
    {
        List<IssueEntry> alreadyAnchored =
            List.of(new IssueEntry(issue("k1", 2).withAnchor(LineAnchor.of("whatever it was")), RELATIVE_PATH));

        List<IssueEntry> anchored = IssueAnchors.anchor(project, alreadyAnchored);

        assertSame(alreadyAnchored, anchored);
        assertEquals(LineAnchor.of("whatever it was"), anchored.get(0).issue().lineAnchor());
    }

    /**
     * The case that makes the server-mode refresh safe. The markers of the previous generation know that
     * issue {@code k1} sits on the line reading {@code Б = 2;}; the server, which has not re-analyzed since
     * the local suppression that pushed that line down, reports it two lines higher. Re-fingerprinting the
     * line the server named would faithfully record the wrong line - so the anchor the markers carry wins,
     * as long as it still describes a line nearby.
     */
    @Test
    public void carriesOverTheAnchorAnEarlierGenerationRecordedOnTheMarker() throws CoreException
    {
        String anchorOfTheRealLine = LineAnchor.of("    Б = 2;");
        marker("k1", 3, anchorOfTheRealLine);

        List<IssueEntry> anchored = IssueAnchors.anchor(project, List.of(entry("k1", 1)));

        assertEquals(anchorOfTheRealLine, anchored.get(0).issue().lineAnchor());
    }

    /**
     * The same carry-over, from the only source left when the user switches editor markers off: the caller's
     * own previous snapshot. Without it the safety net disappears exactly where it is the last one - with no
     * markers there is no Problems-view quick fix either, so the issues view is the only way to suppress at
     * all.
     */
    @Test
    public void carriesOverTheAnchorTheCallerAlreadyKnewWhenThereAreNoMarkers()
    {
        String anchorOfTheRealLine = anchorOfSourceLine(3);

        List<IssueEntry> anchored =
            IssueAnchors.anchor(project, List.of(entry("k1", 1)), Map.of("k1", anchorOfTheRealLine));

        assertEquals(anchorOfTheRealLine, anchored.get(0).issue().lineAnchor());
    }

    /**
     * The escape hatch that keeps the carry-over from becoming a trap: once the anchored line really is gone
     * from the file, holding on to it would make the issue permanently unsuppressable, so the current line
     * is fingerprinted instead. Both carry-over sources are subject to it.
     */
    @Test
    public void abandonsACarriedAnchorWhoseLineNoLongerExists() throws CoreException
    {
        marker("k1", 2, LineAnchor.of("this line was deleted long ago;"));

        List<IssueEntry> anchored = IssueAnchors.anchor(project, List.of(entry("k1", 2)),
            Map.of("k2", LineAnchor.of("this one is gone too;")));

        assertEquals(anchorOfSourceLine(2), anchored.get(0).issue().lineAnchor());
        assertEquals(anchorOfSourceLine(2),
            IssueAnchors.anchor(project, List.of(entry("k2", 2)),
                Map.of("k2", LineAnchor.of("this one is gone too;"))).get(0).issue().lineAnchor());
    }

    /** The two carry-over sources normally agree; when they do not, the marker on the file is the newer one. */
    @Test
    public void theMarkersAnchorWinsOverTheOneTheCallerRemembered() throws CoreException
    {
        String fromTheMarker = LineAnchor.of("    Б = 2;");
        marker("k1", 3, fromTheMarker);

        List<IssueEntry> anchored = IssueAnchors.anchor(project, List.of(entry("k1", 2)),
            Map.of("k1", LineAnchor.of("    А = 1;")));

        assertEquals(fromTheMarker, anchored.get(0).issue().lineAnchor());
    }

    @Test
    public void anchorsOfCollectsTheAnchorsOfASnapshotByIssueKey()
    {
        IssueSnapshot snapshot = new IssueSnapshot(new IssueQuery(PROJECT_KEY, null),
            List.of(issue("k1", 2).withAnchor("v2:0000000000000001:0000000000000002:0000000000000003"),
                issue("k2", 3)),
            2, Instant.EPOCH);

        assertEquals(Map.of("k1", "v2:0000000000000001:0000000000000002:0000000000000003"),
            IssueAnchors.anchorsOf(snapshot));
        assertEquals(Map.of(), IssueAnchors.anchorsOf(null));
    }

    @Test
    public void anchorsAWholeSnapshotThroughItsComponentKeys()
    {
        IssueSnapshot snapshot = new IssueSnapshot(new IssueQuery(PROJECT_KEY, null),
            List.of(issue("k1", 2), issue("k2", 3)), 2, Instant.EPOCH);

        IssueSnapshot anchored = IssueAnchors.anchor(project, PROJECT_KEY, "", snapshot);

        assertEquals(anchorOfSourceLine(2), anchored.issues().get(0).lineAnchor());
        assertEquals(anchorOfSourceLine(3), anchored.issues().get(1).lineAnchor());
        assertEquals(snapshot.serverTotal(), anchored.serverTotal());
        assertEquals(snapshot.loadedAt(), anchored.loadedAt());
    }

    /**
     * The whole of limitation B, end to end, with editor markers switched off - i.e. with no markers on the
     * file at all.
     *
     * <p>One issue is suppressed, which pushes the second one down by two lines. The server has not
     * re-analyzed since, so the refresh that follows reports the second issue on its pre-edit line 3, where
     * the file now holds the {@code -on} comment of the first suppression. Anchoring that refresh from the
     * line numbers alone would record the comment as the issue's line; carrying the view's own anchor over
     * keeps the fingerprint of {@code Б = 2;}, and the suppression that follows wraps that statement.
     */
    @Test
    public void withMarkersOffTheViewsOwnAnchorsSurviveARefreshAndStillFindTheRightLine() throws Exception
    {
        IssueSnapshot loaded = IssueAnchors.anchor(project, PROJECT_KEY, "",
            new IssueSnapshot(new IssueQuery(PROJECT_KEY, null), List.of(issue("k1", 2), issue("k2", 3)), 2,
                Instant.EPOCH));
        SonarIssue first = loaded.issues().get(0);
        assertEquals(SuppressionOutcome.INSERTED,
            SuppressionApplier.apply(file, first.line(), "R1", first.lineAnchor(), null));
        IssueSnapshot afterEdit = SuppressionLineShift.applyAfterSuppress(loaded, first);
        String anchorOfTheStatement = afterEdit.issues().get(0).lineAnchor();

        // The refresh: the server's pre-edit line number for the surviving issue, and no markers anywhere.
        IssueSnapshot refreshed = IssueAnchors.anchor(project, PROJECT_KEY, "",
            new IssueSnapshot(new IssueQuery(PROJECT_KEY, null), List.of(issue("k2", 3)), 1, Instant.EPOCH),
            IssueAnchors.anchorsOf(afterEdit));

        SonarIssue survivor = refreshed.issues().get(0);
        assertEquals("the pre-edit anchor must have been carried over", anchorOfTheStatement,
            survivor.lineAnchor());
        assertEquals(SuppressionOutcome.INSERTED,
            SuppressionApplier.apply(file, survivor.line(), "R2", survivor.lineAnchor(), null));
        assertEquals("Процедура П()\n"
            + "    // BSLLS:R1-off\n"
            + "    А = 1;\n"
            + "    // BSLLS:R1-on\n"
            + "    // BSLLS:R2-off\n"
            + "    Б = 2;\n"
            + "    // BSLLS:R2-on\n"
            + "КонецПроцедуры\n", onDisk());
    }

    private String onDisk() throws IOException
    {
        return Files.readString(file.getLocation().toFile().toPath(), StandardCharsets.UTF_8);
    }

    private IMarker marker(String issueKey, int line, String anchor) throws CoreException
    {
        IMarker marker = file.createMarker(IssueMarkers.MARKER_TYPE);
        marker.setAttribute(IMarker.LINE_NUMBER, line);
        marker.setAttribute(IssueMarkers.ATTR_ISSUE_KEY, issueKey);
        marker.setAttribute(IssueMarkers.ATTR_LINE_ANCHOR, anchor);
        return marker;
    }

    /**
     * The anchor of one line of the pristine module, as {@link IssueAnchors} computes it from the file.
     *
     * @param line1Based the 1-based line number
     * @return the expected anchor
     */
    private static String anchorOfSourceLine(int line1Based)
    {
        return LineAnchor.of(new Document(SOURCE), line1Based);
    }

    private static IssueEntry entry(String key, int line)
    {
        return new IssueEntry(issue(key, line), RELATIVE_PATH);
    }

    private static SonarIssue issue(String key, int line)
    {
        return new SonarIssue(key, "bsl:Rule", SonarSeverity.MAJOR, SonarIssueType.CODE_SMELL,
            PROJECT_KEY + ":" + RELATIVE_PATH, "boom", line);
    }
}
