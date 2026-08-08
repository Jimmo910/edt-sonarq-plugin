/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.ui.resources;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.jobs.Job;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import ru.jimmo.edt.sonarq.core.anchors.AnchorIndex;
import ru.jimmo.edt.sonarq.core.anchors.AnchorIndexStore;
import ru.jimmo.edt.sonarq.core.anchors.AnchorRecord;
import ru.jimmo.edt.sonarq.core.anchors.AnchorScope;
import ru.jimmo.edt.sonarq.core.anchors.TempDirectories;
import ru.jimmo.edt.sonarq.core.client.SonarConnection;
import ru.jimmo.edt.sonarq.core.model.BranchInfo;
import ru.jimmo.edt.sonarq.core.model.IssueQuery;
import ru.jimmo.edt.sonarq.core.model.IssueSnapshot;
import ru.jimmo.edt.sonarq.core.model.SonarIssue;
import ru.jimmo.edt.sonarq.core.model.SonarIssueType;
import ru.jimmo.edt.sonarq.core.model.SonarSeverity;
import ru.jimmo.edt.sonarq.core.provider.IIssueProvider;
import ru.jimmo.edt.sonarq.core.settings.ProjectBinding;
import ru.jimmo.edt.sonarq.core.suppress.LineAnchor;
import ru.jimmo.edt.sonarq.core.suppress.SuppressionOutcome;
import ru.jimmo.edt.sonarq.core.suppress.SuppressionResult;
import ru.jimmo.edt.sonarq.ui.markers.IssueMarkerSynchronizer;
import ru.jimmo.edt.sonarq.ui.markers.IssueMarkers;
import ru.jimmo.edt.sonarq.ui.markers.MarkerStateVersion;
import ru.jimmo.edt.sonarq.ui.markers.MarkerSyncJob;
import ru.jimmo.edt.sonarq.ui.markers.MarkerSyncResult;
import ru.jimmo.edt.sonarq.ui.suppress.SuppressionApplier;
import ru.jimmo.edt.sonarq.ui.sync.ProjectRefreshInputs;
import ru.jimmo.edt.sonarq.ui.views.IssueEntry;
import ru.jimmo.edt.sonarq.ui.views.IssueTreeBuilder;
import ru.jimmo.edt.sonarq.ui.views.RefreshIssuesJob;
import ru.jimmo.edt.sonarq.ui.views.RefreshResult;

/**
 * Tests the anchor memory as the plug-in actually uses it: reconciled once per refresh, persisted, and still
 * there after everything that used to hold it has gone.
 *
 * <p>The scenario every one of these circles is the one the design exists for. In server mode SonarQube keeps
 * reporting the line numbers of its last analysis, which know nothing about a suppression written locally
 * since. The anchors are what turns those numbers back into the right lines - and until this change they were
 * kept only on transient workspace markers and in an open view's snapshot, so a restart, or simply switching
 * editor markers off, silently reduced the safety net to "trust the number".
 */
public class AnchorMemoryReconciliationTest
{
    private static final String PROJECT_NAME = "anchor-memory-test";

    private static final String PROJECT_KEY = "proj";

    private static final String SERVER_URL = "https://sonar.example";

    private static final String RELATIVE_PATH = "src/Module.bsl";

    private static final String SOURCE = "Procedure P()\n"
        + "    First = 1;\n"
        + "    Second = 2;\n"
        + "    Third = 3;\n"
        + "EndProcedure\n";

    private static final long JOB_TIMEOUT_MILLIS = 60_000L;

    private IProject project;

    private IFile file;

    private Path storeRoot;

    @Before
    public void setUp() throws CoreException, IOException
    {
        project = ResourcesPlugin.getWorkspace().getRoot().getProject(PROJECT_NAME);
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
        storeRoot = Files.createTempDirectory("anchor-memory-test");
    }

    @After
    public void tearDown() throws CoreException
    {
        project.delete(true, true, new NullProgressMonitor());
        TempDirectories.delete(storeRoot);
    }

    /**
     * The motivating scenario, end to end and across a restart.
     *
     * <p>A first refresh anchors two issues. One is suppressed, which pushes the other down by two lines. The
     * store is then reopened from scratch - a fresh instance, no markers on the file, no view, i.e. exactly
     * what EDT looks like after it is restarted - and a second refresh reports the surviving issue on the line
     * SonarQube still remembers. The persisted anchor has to find the statement where it really is now, and
     * the suppression that follows has to wrap <em>that</em> line.
     *
     * <p>Without the persisted memory this test does not merely fail to relocate: the second refresh
     * fingerprints whatever sits on the stale line - the code of the <em>first</em> suppression - and the
     * second quick-suppress wraps the wrong statement, in a file it has already edited once.
     */
    @Test
    public void anchorsSurviveARestartAndStillFindTheRightLine() throws Exception
    {
        AnchorIndexStore first = store();
        AnchorIndex index = first.load(scope());
        IssueSnapshot loaded = IssueAnchors.reconcile(project, PROJECT_KEY, "",
            snapshot(issue("k1", 2), issue("k2", 3)), Map.of(), index, 1000);
        assertTrue(first.save(index, () -> true));
        SonarIssue suppressed = loaded.issues().get(0);
        SuppressionResult result =
            SuppressionApplier.apply(file, suppressed.line(), "R1", suppressed.lineAnchor(), null);
        assertEquals(SuppressionOutcome.INSERTED, result.outcome());
        first.suppressionApplied(PROJECT_NAME, RELATIVE_PATH, "k1", result.line());

        // The restart: a brand new store instance, no markers anywhere, no view, and a server that replays
        // the line numbers of its last analysis.
        AnchorIndexStore reopened = store();
        AnchorIndex restored = reopened.load(scope());
        IssueSnapshot refreshed = IssueAnchors.reconcile(project, PROJECT_KEY, "", snapshot(issue("k2", 3)),
            Map.of(), restored, 2000);

        SonarIssue survivor = refreshed.issues().get(0);
        assertEquals("the anchor must have found the statement where it really is now", 5, survivor.line());
        assertEquals(SuppressionOutcome.INSERTED,
            SuppressionApplier.apply(file, survivor.line(), "R2", survivor.lineAnchor(), null).outcome());
        assertEquals("Procedure P()\n"
            + "    // BSLLS:R1-off\n"
            + "    First = 1;\n"
            + "    // BSLLS:R1-on\n"
            + "    // BSLLS:R2-off\n"
            + "    Second = 2;\n"
            + "    // BSLLS:R2-on\n"
            + "    Third = 3;\n"
            + "EndProcedure\n", onDisk());
    }

    /**
     * The background auto-sync with editor markers switched off: no markers are created and no view exists,
     * and the anchor memory still has to be written. That preference gates the delivery of anchors, never the
     * anchoring itself - which is exactly the configuration in which the safety net used to vanish, because
     * with no markers there is no Problems-view quick fix either and the view's own menu is the only way to
     * suppress at all.
     */
    @Test(timeout = JOB_TIMEOUT_MILLIS)
    public void arefreshWritesAnchorMemoryWithNoMarkersAndNoView() throws Exception
    {
        AnchorIndexStore store = store();

        RefreshResult result = runRefresh(store, snapshot(issue("k1", 2), issue("k2", 3)));

        assertFalse(result.isError());
        assertEquals("no markers may have been created", 0,
            file.findMarkers(IssueMarkers.MARKER_TYPE, true, IResource.DEPTH_ZERO).length);
        AnchorIndex persisted = store().load(scope());
        assertEquals(2, persisted.size());
        assertNotNull(persisted.find(RELATIVE_PATH, "k1"));
        assertEquals(3, persisted.find(RELATIVE_PATH, "k2").lastKnownLine());
        assertTrue("the snapshot handed on must already be anchored",
            result.snapshot().issues().stream().noneMatch(issue -> issue.lineAnchor().isEmpty()));
    }

    /**
     * Switching editor markers off deletes every marker in the workspace (see
     * {@code ru.jimmo.edt.sonarq.ui.SonarqStartup}). The memory is the plug-in's own and must not go with
     * them - it is precisely what has to keep working once the markers are not there to carry anything.
     */
    @Test
    public void turningMarkersOffDeletesMarkersAndLeavesTheMemoryIntact() throws Exception
    {
        AnchorIndexStore store = store();
        AnchorIndex index = store.load(scope());
        IssueAnchors.reconcile(project, PROJECT_KEY, "", snapshot(issue("k1", 3)), Map.of(), index, 1000);
        store.save(index, () -> true);
        new IssueMarkerSynchronizer().sync(project, entries(issue("k1", 3).withAnchor(anchorOfLine(3))));
        assertEquals(1, file.findMarkers(IssueMarkers.MARKER_TYPE, true, IResource.DEPTH_ZERO).length);

        new IssueMarkerSynchronizer().clearAll();

        assertEquals(0, file.findMarkers(IssueMarkers.MARKER_TYPE, true, IResource.DEPTH_ZERO).length);
        AnchorRecord remembered = store().load(scope()).find(RELATIVE_PATH, "k1");
        assertNotNull("the memory must outlive the markers", remembered);
        assertEquals(anchorOfLine(3), remembered.anchor());
    }

    /**
     * The refusal that must stay a refusal. When a stored anchor can no longer be found in the file - the
     * user rewrote that code - the memory is left exactly as it is and the next suppression declines. What
     * must <em>not</em> happen is a fresh fingerprint of whatever the analysis named instead: that turns a
     * safe refusal into a confident edit of a line nobody verified.
     */
    @Test
    public void astoredAnchorThatNoLongerResolvesIsNeverReplacedByTheReportedLine()
    {
        AnchorIndexStore store = store();
        AnchorIndex index = store.load(scope());
        String gone = LineAnchor.of("Nothing in this file ever looked like this;");
        index.put(RELATIVE_PATH, new AnchorRecord("k1", "bsl:R", gone, 3, 1000));
        store.save(index, () -> true);

        AnchorIndex reloaded = store.load(scope());
        IssueSnapshot refreshed = IssueAnchors.reconcile(project, PROJECT_KEY, "", snapshot(issue("k1", 2)),
            Map.of(), reloaded, 2000);

        SonarIssue reconciled = refreshed.issues().get(0);
        assertEquals("the unresolvable anchor must be kept", gone, reconciled.lineAnchor());
        assertFalse("it must not have become a fingerprint of the reported line",
            anchorOfLine(2).equals(reconciled.lineAnchor()));
        assertEquals("the issue stays where the analysis put it", 2, reconciled.line());
        AnchorRecord remembered = reloaded.find(RELATIVE_PATH, "k1");
        assertEquals(gone, remembered.anchor());
        assertEquals("the hint is not evidence either, so it is left alone", 3,
            remembered.lastKnownLine());
        assertEquals("and the suppression refuses", SuppressionOutcome.ANCHOR_NOT_FOUND,
            suppress(reconciled).outcome());
    }

    /** A record the memory has never heard of is fingerprinted where the analysis says it is. */
    @Test
    public void anIssueWithNoMemoryIsAnchoredAtItsReportedLine()
    {
        AnchorIndex index = new AnchorIndex(scope());

        IssueSnapshot refreshed = IssueAnchors.reconcile(project, PROJECT_KEY, "", snapshot(issue("k1", 3)),
            Map.of(), index, 1000);

        assertEquals(anchorOfLine(3), refreshed.issues().get(0).lineAnchor());
        assertEquals(3, index.find(RELATIVE_PATH, "k1").lastKnownLine());
    }

    /** An issue reported past the end of the file cannot be fingerprinted, so it is remembered as nothing. */
    @Test
    public void anIssueBeyondTheEndOfTheFileIsLeftUnanchoredAndUnremembered()
    {
        AnchorIndex index = new AnchorIndex(scope());

        IssueSnapshot refreshed = IssueAnchors.reconcile(project, PROJECT_KEY, "", snapshot(issue("k1", 999)),
            Map.of(), index, 1000);

        assertEquals(LineAnchor.NONE, refreshed.issues().get(0).lineAnchor());
        assertEquals(0, index.size());
    }

    /** Two analyses of the same project never read each other's records. */
    @Test
    public void memoryOfOneScopeIsInvisibleToAnother()
    {
        AnchorIndexStore store = store();
        AnchorIndex main = store.load(scope());
        IssueAnchors.reconcile(project, PROJECT_KEY, "", snapshot(issue("k1", 3)), Map.of(), main, 1000);
        store.save(main, () -> true);

        AnchorScope otherBranch = new AnchorScope(AnchorScope.MODE_SERVER, SERVER_URL,
            PROJECT_KEY, "release/1", "", PROJECT_NAME);

        assertNull(store.load(otherBranch).find(RELATIVE_PATH, "k1"));
        assertNotNull(store.load(scope()).find(RELATIVE_PATH, "k1"));
    }

    /**
     * The fencing hole, closed. The state version used to be minted when a marker synchronization job was
     * <em>constructed</em>, i.e. after the fetch had already returned - so a refresh that started before a
     * quick-suppress and finished after it published a version newer than the suppression's, passed its own
     * fence, and wrote pre-edit line numbers over the edited file. The version is now reserved before the
     * fetch, and both the memory commit and the marker write are checked against that reservation.
     */
    @Test(timeout = JOB_TIMEOUT_MILLIS)
    public void arefreshOvertakenByASuppressionUpdatesNeitherTheMemoryNorTheMarkers() throws Exception
    {
        AnchorIndexStore store = store();
        AnchorIndex index = store.load(scope());
        IssueSnapshot loaded = IssueAnchors.reconcile(project, PROJECT_KEY, "",
            snapshot(issue("k1", 2), issue("k2", 3)), Map.of(), index, 1000);
        store.save(index, () -> true);
        runMarkerSync(entries(loaded.issues().toArray(new SonarIssue[0])),
            MarkerStateVersion.publish(project));

        // The refresh starts here and reserves its version; the suppression lands while its provider is
        // "fetching", exactly as a click during a slow server round trip would.
        RefreshResult result = runRefresh(store, snapshot(issue("k1", 2), issue("k2", 3)), () ->
        {
            SonarIssue victim = loaded.issues().get(0);
            SuppressionResult edit = suppress(victim);
            assertEquals(SuppressionOutcome.INSERTED, edit.outcome());
            MarkerStateVersion.publish(project);
            store.suppressionApplied(PROJECT_NAME, RELATIVE_PATH, victim.key(), edit.line());
        });

        AnchorIndex afterwards = store().load(scope());
        assertNull("the refresh must not have resurrected the suppressed issue",
            afterwards.find(RELATIVE_PATH, "k1"));
        assertEquals("the post-edit hint must not have been overwritten with the pre-edit one", 5,
            afterwards.find(RELATIVE_PATH, "k2").lastKnownLine());
        MarkerSyncResult markers = runMarkerSync(
            IssueTreeBuilder.toEntries(result.snapshot().issues(), PROJECT_KEY, ""),
            result.markerStateVersion());
        assertTrue("the marker write must be abandoned too", markers.abandoned());
    }

    /**
     * Runs one refresh through the production job, against a fake provider.
     *
     * @param store the anchor memory to use
     * @param snapshot what the provider reports
     * @return the refresh outcome
     * @throws InterruptedException when the wait is interrupted
     */
    private RefreshResult runRefresh(AnchorIndexStore store, IssueSnapshot snapshot)
        throws InterruptedException
    {
        return runRefresh(store, snapshot, () ->
        {
            // Nothing happens while this refresh is in flight.
        });
    }

    /**
     * Runs one refresh through the production job, letting the caller act while the provider is "fetching".
     *
     * @param store the anchor memory to use
     * @param snapshot what the provider reports
     * @param whileFetching runs inside the provider call, i.e. after the refresh has reserved its state
     *     version and before it commits anything
     * @return the refresh outcome
     * @throws InterruptedException when the wait is interrupted
     */
    private RefreshResult runRefresh(AnchorIndexStore store, IssueSnapshot snapshot, Runnable whileFetching)
        throws InterruptedException
    {
        ProjectRefreshInputs inputs = new ProjectRefreshInputs(project, new ProjectBinding(PROJECT_KEY, "", ""),
            SonarConnection.of(SERVER_URL, "a-secret-token", 30), new FakeProvider(snapshot, whileFetching),
            PROJECT_KEY, "");
        AtomicReference<RefreshResult> result = new AtomicReference<>();
        Job job = new RefreshIssuesJob(inputs.provider(), project, inputs.binding(), null, result::set,
            new RefreshAnchoring(inputs, Map.of(), store));
        job.schedule();
        job.join();
        return result.get();
    }

    /**
     * Runs one marker synchronization to completion.
     *
     * @param entries the entries to materialize
     * @param stateVersion the version the write carries
     * @return the outcome
     * @throws InterruptedException when the wait is interrupted
     */
    private MarkerSyncResult runMarkerSync(List<IssueEntry> entries, long stateVersion)
        throws InterruptedException
    {
        AtomicReference<MarkerSyncResult> reported = new AtomicReference<>();
        MarkerSyncJob job = new MarkerSyncJob(project, () -> entries, reported::set, stateVersion);
        job.schedule();
        job.join();
        return reported.get() != null ? reported.get() : MarkerSyncResult.superseded();
    }

    private SuppressionResult suppress(SonarIssue issue)
    {
        try
        {
            return SuppressionApplier.apply(file, issue.line(), "R1", issue.lineAnchor(), null);
        }
        catch (Exception e)
        {
            throw new IllegalStateException(e);
        }
    }

    private String onDisk() throws IOException
    {
        return Files.readString(file.getLocation().toFile().toPath(), StandardCharsets.UTF_8);
    }

    private static String anchorOfLine(int line1Based)
    {
        return LineAnchor.of(new org.eclipse.jface.text.Document(SOURCE), line1Based);
    }

    private static AnchorScope scope()
    {
        return new AnchorScope(AnchorScope.MODE_SERVER, SERVER_URL, PROJECT_KEY, "main", "",
            PROJECT_NAME);
    }

    private AnchorIndexStore store()
    {
        return new AnchorIndexStore(storeRoot, (message, failure) ->
        {
            // Failures are asserted through behaviour here; AnchorIndexStoreTest pins the reporting itself.
        });
    }

    private static IssueSnapshot snapshot(SonarIssue... issues)
    {
        return new IssueSnapshot(new IssueQuery(PROJECT_KEY, "main"), List.of(issues), issues.length,
            Instant.EPOCH);
    }

    private static List<IssueEntry> entries(SonarIssue... issues)
    {
        return List.of(issues).stream().map(issue -> new IssueEntry(issue, RELATIVE_PATH)).toList();
    }

    private static SonarIssue issue(String key, int line)
    {
        return new SonarIssue(key, "bsl:Rule", SonarSeverity.MAJOR, SonarIssueType.CODE_SMELL,
            PROJECT_KEY + ":" + RELATIVE_PATH, "boom", line);
    }

    /** Reports a fixed snapshot, running the caller's hook while it "fetches". */
    private static final class FakeProvider implements IIssueProvider
    {
        private final IssueSnapshot snapshot;

        private final Runnable whileFetching;

        FakeProvider(IssueSnapshot snapshot, Runnable whileFetching)
        {
            this.snapshot = snapshot;
            this.whileFetching = whileFetching;
        }

        @Override
        public IssueSnapshot fetchIssues(IssueQuery query, IProgressMonitor monitor)
        {
            // On a thread of its own, because the caller runs under the refresh job's ProjectAnalysisRule and
            // a real quick-suppress - which commits through a text file buffer, and so begins the edited
            // file's resource rule - could never happen there. The user's click does not, either.
            Thread editor = new Thread(whileFetching, "suppression-during-refresh");
            editor.start();
            try
            {
                editor.join();
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
            }
            return snapshot;
        }

        @Override
        public List<BranchInfo> listBranches(String projectKey)
        {
            return List.of(new BranchInfo("main", true));
        }

        @Override
        public boolean branchAnalysisSupported()
        {
            return true;
        }
    }
}
