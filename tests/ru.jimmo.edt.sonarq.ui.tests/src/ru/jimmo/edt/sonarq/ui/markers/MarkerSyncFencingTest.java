/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.ui.markers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

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
import ru.jimmo.edt.sonarq.ui.views.IssueEntry;

/**
 * Tests the fence that decides whether a marker synchronization may still write (see
 * {@link MarkerStateVersion}).
 *
 * <p>The project scheduling rule of {@link MarkerSyncJob} serializes the marker writes, so no half-written
 * marker set is ever visible - but it says nothing about their order. A job queued with an older snapshot (a
 * refresh that finished just before a quick-suppress) and released after a newer state has been published
 * used to delete the newer markers and recreate its own, putting pre-edit line numbers back on an
 * already-edited file.
 */
public class MarkerSyncFencingTest
{
    private static final String PROJECT_NAME = "marker-fencing-test";

    private static final String RELATIVE_PATH = "src/Module.bsl";

    private static final long JOB_TIMEOUT_MILLIS = 60_000L;

    private IProject project;

    private IFile file;

    @Before
    public void setUp() throws CoreException
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
        if (!file.exists())
        {
            file.create(new ByteArrayInputStream("test".getBytes(StandardCharsets.UTF_8)), true,
                new NullProgressMonitor());
        }
    }

    @After
    public void tearDown() throws CoreException
    {
        project.delete(true, true, new NullProgressMonitor());
    }

    /**
     * The defect: a job constructed with an older state, released after a newer state has already been
     * written, must leave the newer markers exactly as they are.
     */
    @Test(timeout = JOB_TIMEOUT_MILLIS)
    public void aJobCarryingASupersededStateVersionDoesNotTouchTheMarkers()
        throws CoreException, InterruptedException
    {
        // The stale producer hands its snapshot over first - and is then held up (a slow anchoring read, a
        // busy project rule) while a newer state is produced and written.
        MarkerSyncJob stale = new MarkerSyncJob(project, () -> List.of(entry("stale", 99)));
        run(new MarkerSyncJob(project, () -> List.of(entry("current", 12))));

        run(stale);

        IMarker[] markers = file.findMarkers(IssueMarkers.MARKER_TYPE, true, IResource.DEPTH_ZERO);
        assertEquals("the newer state's markers must survive", 1, markers.length);
        assertEquals("current", markers[0].getAttribute(IssueMarkers.ATTR_ISSUE_KEY, ""));
        assertEquals(12, markers[0].getAttribute(IMarker.LINE_NUMBER, -1));
    }

    /**
     * A superseded run reports nothing back either: its counts describe a marker set that was never written,
     * and the issues view folds the missing-file count of the result it receives straight into its status
     * line. It also never evaluates its entry supplier, so the file reads of the anchoring are skipped too.
     */
    @Test(timeout = JOB_TIMEOUT_MILLIS)
    public void aSupersededRunNeitherReportsBackNorEvaluatesItsEntries() throws InterruptedException
    {
        AtomicReference<MarkerSyncResult> reported = new AtomicReference<>();
        AtomicInteger supplierCalls = new AtomicInteger();
        MarkerSyncJob stale = new MarkerSyncJob(project, () ->
        {
            supplierCalls.incrementAndGet();
            return List.of(entry("stale", 99));
        }, reported::set);
        run(new MarkerSyncJob(project, () -> List.of(entry("current", 12))));

        run(stale);

        assertNull("a run that wrote nothing must not report a marker state", reported.get());
        assertEquals(0, supplierCalls.get());
    }

    /** The ordinary case must keep working: the newest state is the one that gets written. */
    @Test(timeout = JOB_TIMEOUT_MILLIS)
    public void theNewestStateIsStillWritten() throws CoreException, InterruptedException
    {
        AtomicReference<MarkerSyncResult> reported = new AtomicReference<>();
        run(new MarkerSyncJob(project, () -> List.of(entry("first", 3))));

        run(new MarkerSyncJob(project, () -> List.of(entry("second", 8)), reported::set));

        IMarker[] markers = file.findMarkers(IssueMarkers.MARKER_TYPE, true, IResource.DEPTH_ZERO);
        assertEquals(1, markers.length);
        assertEquals("second", markers[0].getAttribute(IssueMarkers.ATTR_ISSUE_KEY, ""));
        assertEquals(8, markers[0].getAttribute(IMarker.LINE_NUMBER, -1));
        assertEquals(1, reported.get().created());
        assertFalse(reported.get().abandoned());
    }

    /**
     * The check itself, where it has to happen: inside the workspace operation, under the project's rule,
     * before the first marker is deleted. A synchronization whose fence says "superseded" deletes nothing.
     */
    @Test
    public void aSynchronizationWhoseFenceSaysSupersededDeletesNothing() throws CoreException
    {
        IssueMarkerSynchronizer synchronizer = new IssueMarkerSynchronizer();
        synchronizer.sync(project, List.of(entry("keep", 5)));

        MarkerSyncResult result = synchronizer.sync(project, List.of(entry("overwrite", 6)), () -> false);

        assertTrue(result.abandoned());
        IMarker[] markers = file.findMarkers(IssueMarkers.MARKER_TYPE, true, IResource.DEPTH_ZERO);
        assertEquals(1, markers.length);
        assertEquals("keep", markers[0].getAttribute(IssueMarkers.ATTR_ISSUE_KEY, ""));
    }

    /** Versions are per project: a state published for one project may not fence another project's write. */
    @Test
    public void versionsOfDifferentProjectsAreIndependent()
    {
        IProject other = ResourcesPlugin.getWorkspace().getRoot().getProject(PROJECT_NAME + "-other");
        long mine = MarkerStateVersion.publish(project);

        MarkerStateVersion.publish(other);

        assertTrue(MarkerStateVersion.isCurrent(project, mine));
        assertFalse(MarkerStateVersion.isCurrent(project, mine - 1));
    }

    /**
     * Runs one synchronization job to completion.
     *
     * @param job the job to run
     * @throws InterruptedException when the wait is interrupted
     */
    private static void run(MarkerSyncJob job) throws InterruptedException
    {
        job.schedule();
        job.join();
    }

    private static IssueEntry entry(String key, int line)
    {
        SonarIssue issue = new SonarIssue(key, "bsl:Rule", SonarSeverity.MAJOR, SonarIssueType.CODE_SMELL,
            "proj:" + RELATIVE_PATH, "boom", line);
        return new IssueEntry(issue, RELATIVE_PATH);
    }
}
