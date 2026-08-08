/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.ui.sync;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import ru.jimmo.edt.sonarq.core.model.SonarIssue;
import ru.jimmo.edt.sonarq.core.model.SonarIssueType;
import ru.jimmo.edt.sonarq.core.model.SonarSeverity;
import ru.jimmo.edt.sonarq.ui.markers.IssueMarkers;
import ru.jimmo.edt.sonarq.ui.markers.MarkerStateVersion;
import ru.jimmo.edt.sonarq.ui.views.IssueEntry;

/**
 * Regression tests for the background auto-sync marker path.
 *
 * <p>{@link AutoSyncScheduler} used to synchronize markers straight from the refresh job's callback, which
 * runs in the refresh job's thread under a {@link ProjectAnalysisRule}. Marker synchronization begins the
 * project's resource rule, and the Eclipse job manager rejects a rule the running job's rule neither
 * contains nor conflicts with ({@code ThreadJob.illegalPush}: "Attempted to beginRule: ..., does not match
 * outer scope rule: ..."), so every timer cycle threw {@link IllegalArgumentException} and no marker was
 * ever updated in the background.
 */
public class AutoSyncMarkerSyncTest
{
    private static final String PROJECT_NAME = "auto-sync-marker-test";

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
     * Documents why marker synchronization may not be nested inside the analysis rule: the rule contains
     * (and conflicts with) nothing but another rule of the same project analysis, so it does not cover the
     * project's resource rule that the synchronization needs. The job manager demands both.
     */
    @Test
    public void analysisRuleNeitherContainsNorConflictsWithTheProjectResourceRule()
    {
        ProjectAnalysisRule rule = new ProjectAnalysisRule(PROJECT_NAME);

        assertFalse("the analysis rule must not contain the project resource rule", rule.contains(project));
        assertFalse("the analysis rule must not conflict with resource work", rule.isConflicting(project));
        assertTrue("the analysis rule must still serialize with itself",
            rule.contains(new ProjectAnalysisRule(PROJECT_NAME)));
    }

    /**
     * The regression test proper: runs the scheduler's marker-synchronization seam from inside a job that
     * holds the {@link ProjectAnalysisRule} - exactly the thread and rule the refresh job's callback runs
     * under - and requires it to finish without an {@link IllegalArgumentException} and to actually create
     * the markers. Synchronizing in that thread instead of in a job of its own fails here.
     */
    @Test(timeout = JOB_TIMEOUT_MILLIS)
    public void markerSyncFromInsideAnalysisRuleJobCreatesMarkers() throws CoreException, InterruptedException
    {
        AtomicReference<RuntimeException> failure = new AtomicReference<>();
        AtomicReference<Boolean> completed = new AtomicReference<>();
        Job analysisJob = new Job("analysis-rule-holder")
        {
            @Override
            protected IStatus run(IProgressMonitor monitor)
            {
                try
                {
                    completed.set(AutoSyncScheduler.syncMarkers(project, () -> List.of(entry("k1", 7)),
                        MarkerStateVersion.publish(project)));
                }
                catch (RuntimeException e)
                {
                    failure.set(e);
                }
                return Status.OK_STATUS;
            }
        };
        analysisJob.setRule(new ProjectAnalysisRule(PROJECT_NAME));

        analysisJob.schedule();
        analysisJob.join();

        assertNull(String.valueOf(failure.get()), failure.get());
        assertEquals(Boolean.TRUE, completed.get());
        IMarker[] markers = file.findMarkers(IssueMarkers.MARKER_TYPE, true, IResource.DEPTH_ZERO);
        assertEquals(1, markers.length);
        assertEquals(7, markers[0].getAttribute(IMarker.LINE_NUMBER, -1));
    }

    /**
     * The same seam from a plain thread that holds no rule at all: it must synchronize the markers too, and
     * only return once the synchronization job has finished, so one auto-sync cycle never overlaps the next.
     */
    @Test(timeout = JOB_TIMEOUT_MILLIS)
    public void markerSyncWaitsForTheSynchronizationJobToFinish() throws CoreException
    {
        assertTrue(AutoSyncScheduler.syncMarkers(project, () -> List.of(entry("k2", 3)),
            MarkerStateVersion.publish(project)));

        assertEquals(1, file.findMarkers(IssueMarkers.MARKER_TYPE, true, IResource.DEPTH_ZERO).length);

        assertTrue(AutoSyncScheduler.syncMarkers(project, List::of, MarkerStateVersion.publish(project)));

        assertEquals(0, file.findMarkers(IssueMarkers.MARKER_TYPE, true, IResource.DEPTH_ZERO).length);
    }

    private static IssueEntry entry(String key, int line)
    {
        SonarIssue issue = new SonarIssue(key, "bsl:Rule", SonarSeverity.MAJOR, SonarIssueType.CODE_SMELL,
            "proj:" + RELATIVE_PATH, "boom", line);
        return new IssueEntry(issue, RELATIVE_PATH);
    }
}
