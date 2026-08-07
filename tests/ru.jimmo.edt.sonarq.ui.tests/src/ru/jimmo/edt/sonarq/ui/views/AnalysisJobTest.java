/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.ui.views;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Status;
import org.eclipse.osgi.util.NLS;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import ru.jimmo.edt.sonarq.core.analysis.AnalysisLaunchConfig;
import ru.jimmo.edt.sonarq.core.analysis.AnalysisLaunchMode;
import ru.jimmo.edt.sonarq.core.analysis.ICiTrigger;
import ru.jimmo.edt.sonarq.core.client.ISonarServerClient;
import ru.jimmo.edt.sonarq.core.client.SonarConnection;
import ru.jimmo.edt.sonarq.core.client.SonarServerException;
import ru.jimmo.edt.sonarq.core.model.BranchInfo;
import ru.jimmo.edt.sonarq.core.model.CeTask;
import ru.jimmo.edt.sonarq.core.model.ComponentInfo;
import ru.jimmo.edt.sonarq.core.model.IssueQuery;
import ru.jimmo.edt.sonarq.core.model.IssuesPage;
import ru.jimmo.edt.sonarq.core.settings.ProjectBinding;
import ru.jimmo.edt.sonarq.ui.Messages;

/**
 * Tests for {@link AnalysisJob}.
 *
 * <p>Everything the job does around the scanner is covered here: the CI trigger dispatch (and the transport
 * lifetime of review minor M2), the pre-flight checks that decide whether a scanner is launched at all, the
 * {@code report-task.txt} to Compute-Engine polling handover with its terminal outcomes, cancellation and
 * timeout, the source-root resolution, and the way failures reach the status line. Nothing here starts a
 * process or opens a socket: the scanner is never reached because the executable never resolves, the server
 * is a fake client, and the CI transport is a fake trigger. The polling budget is injected so the timeout
 * path costs milliseconds instead of ten minutes.
 */
public class AnalysisJobTest
{
    private static final String CI_URL = "https://gitlab.example.com/api/v4/projects/1/trigger/pipeline";
    private static final String TASK_ID = "AXf3n2";
    private static final AnalysisJob.PollingBudget FAST_POLLING = new AnalysisJob.PollingBudget(2000L, 5L);

    /** A server client that answers from fields; every unused operation fails loudly. */
    private static final class FakeClient implements ISonarServerClient
    {
        private Set<String> languages = Set.of("bsl");
        private SonarServerException languagesFailure;
        private RuntimeException languagesRuntimeFailure;
        private final Deque<CeTask> tasks = new ArrayDeque<>();
        private CeTask repeatedTask = new CeTask("IN_PROGRESS", "");
        private SonarServerException ceFailure;
        private final List<String> polledTaskIds = new ArrayList<>();

        @Override
        public Set<String> serverLanguages() throws SonarServerException
        {
            if (languagesRuntimeFailure != null)
            {
                throw languagesRuntimeFailure;
            }
            if (languagesFailure != null)
            {
                throw languagesFailure;
            }
            return languages;
        }

        @Override
        public CeTask ceTaskStatus(String taskId) throws SonarServerException
        {
            polledTaskIds.add(taskId);
            if (ceFailure != null)
            {
                throw ceFailure;
            }
            return tasks.isEmpty() ? repeatedTask : tasks.poll();
        }

        @Override
        public String serverVersion()
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public String serverEdition()
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<BranchInfo> listBranches(String projectKey)
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public IssuesPage searchIssuesPage(IssueQuery query, int page)
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<ComponentInfo> searchProjects(String namePart)
        {
            throw new UnsupportedOperationException();
        }
    }

    /** A CI transport that records what it was asked to do, and whether it was released. */
    private static final class FakeTrigger implements ICiTrigger
    {
        private int status = 200;
        private IOException failure;
        private RuntimeException runtimeFailure;
        private boolean closed;
        private String urlTemplate;
        private String branch;
        private String secretHeader;

        @Override
        public int trigger(String template, String branchName, String secret) throws IOException
        {
            this.urlTemplate = template;
            this.branch = branchName;
            this.secretHeader = secret;
            if (runtimeFailure != null)
            {
                throw runtimeFailure;
            }
            if (failure != null)
            {
                throw failure;
            }
            return status;
        }

        @Override
        public void close()
        {
            closed = true;
        }
    }

    private IProject project;
    private Path stateLocation;
    private final List<String> statuses = Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger successes = new AtomicInteger();
    private final FakeClient client = new FakeClient();
    private final FakeTrigger trigger = new FakeTrigger();
    private final List<Integer> requestedTimeouts = new ArrayList<>();

    @Before
    public void setUp() throws CoreException, IOException
    {
        project = ResourcesPlugin.getWorkspace().getRoot().getProject("analysis-job-test");
        if (!project.exists())
        {
            project.create(new NullProgressMonitor());
        }
        project.open(new NullProgressMonitor());
        stateLocation = Files.createTempDirectory("sonarq-analysis-job");
    }

    @After
    public void tearDown() throws CoreException
    {
        project.delete(true, true, new NullProgressMonitor());
    }

    private AnalysisJob job(AnalysisLaunchConfig config, ProjectBinding binding, String branch, String ciSecret)
    {
        AnalysisRequest request = new AnalysisRequest(project, binding,
            SonarConnection.of("https://sonar.example.com", "squ_token", 42), config, branch, true, ciSecret,
            stateLocation, client);
        return new AnalysisJob(request, successes::incrementAndGet, statuses::add, timeout ->
        {
            requestedTimeouts.add(Integer.valueOf(timeout));
            return trigger;
        }, FAST_POLLING);
    }

    private AnalysisJob ciJob(String branch, String ciSecret)
    {
        return job(new AnalysisLaunchConfig(AnalysisLaunchMode.CI_TRIGGER, "", CI_URL + "?token=glptt-1", ""),
            binding(), branch, ciSecret);
    }

    private AnalysisJob scannerJob(String scannerPath)
    {
        return job(new AnalysisLaunchConfig(AnalysisLaunchMode.LOCAL_PATH, scannerPath, "", ""), binding(), "main",
            "");
    }

    /** A job in a scanner mode, used to drive the post-scan polling directly. */
    private AnalysisJob pollingJob()
    {
        return job(new AnalysisLaunchConfig(AnalysisLaunchMode.LOCAL_AUTO, "", "", ""), binding(), "main", "");
    }

    private static ProjectBinding binding()
    {
        return new ProjectBinding("proj", "", "");
    }

    private String lastStatus()
    {
        assertFalse("no status was reported", statuses.isEmpty());
        return statuses.get(statuses.size() - 1);
    }

    // --- CI trigger dispatch -------------------------------------------------------------------------

    @Test
    public void ciTriggerReportsTheServerStatusAndReleasesTheTransport()
    {
        trigger.status = 201;

        IStatus result = ciJob("main", "Bearer ci").run(new NullProgressMonitor());

        assertSame(Status.OK_STATUS, result);
        assertEquals(NLS.bind(Messages.Analysis_CiTriggered, Integer.valueOf(201)), lastStatus());
        assertTrue("the CI transport owns a selector thread and must be closed (M2)", trigger.closed);
    }

    @Test
    public void ciTriggerPassesTheBranchSecretAndConnectionTimeout()
    {
        ciJob("feature/x", "Bearer ci-secret").run(new NullProgressMonitor());

        assertEquals(CI_URL + "?token=glptt-1", trigger.urlTemplate);
        assertEquals("feature/x", trigger.branch);
        assertEquals("Bearer ci-secret", trigger.secretHeader);
        assertEquals(List.of(Integer.valueOf(42)), requestedTimeouts);
    }

    @Test
    public void ciTriggerSubstitutesAnEmptyBranchWhenNoneWasRequested()
    {
        ciJob(null, "").run(new NullProgressMonitor());

        assertEquals("", trigger.branch);
    }

    @Test
    public void ciTriggerReportsANonSuccessStatusAsAFailureAndStillReleasesTheTransport()
    {
        trigger.status = 403;

        ciJob("main", "").run(new NullProgressMonitor());

        assertEquals(NLS.bind(Messages.Analysis_CiFailed, "403"), lastStatus());
        assertTrue(trigger.closed);
    }

    @Test
    public void ciTriggerReportsATransportFailureWithoutLeakingTheTriggerToken()
    {
        trigger.failure = new IOException("POST " + CI_URL + "?token=glptt-1 failed");

        IStatus result = ciJob("main", "").run(new NullProgressMonitor());

        assertSame(Status.OK_STATUS, result);
        assertFalse(lastStatus(), lastStatus().contains("glptt-1"));
        assertTrue(lastStatus(), lastStatus().contains(CI_URL));
        assertTrue(trigger.closed);
    }

    @Test
    public void ciTriggerReportsARuntimeFailureWithoutLeakingTheCiSecret()
    {
        trigger.runtimeFailure = new IllegalArgumentException("bad header value: Bearer ci-secret");

        ciJob("main", "Bearer ci-secret").run(new NullProgressMonitor());

        assertFalse(lastStatus(), lastStatus().contains("ci-secret"));
        assertTrue(trigger.closed);
    }

    // --- pre-flight checks before a scanner is launched ----------------------------------------------

    @Test
    public void serverFailureWhileCheckingLanguagesIsReportedNotThrown()
    {
        client.languagesFailure = new SonarServerException(500, "server is down");

        IStatus result = scannerJob("").run(new NullProgressMonitor());

        assertSame(Status.OK_STATUS, result);
        assertEquals("server is down", lastStatus());
    }

    @Test
    public void aServerWithoutTheBslPluginIsReportedAndNoScannerIsResolved()
    {
        client.languages = Set.of("java", "js");

        scannerJob(existingFile().toString()).run(new NullProgressMonitor());

        assertEquals(Messages.Analysis_NoBslOnServer, lastStatus());
    }

    @Test
    public void aBlankScannerPathIsReportedAsScannerNotFound()
    {
        scannerJob("").run(new NullProgressMonitor());

        assertEquals(Messages.Analysis_ScannerNotFound, lastStatus());
    }

    @Test
    public void aScannerPathThatIsNotAFileIsReportedAsScannerNotFound()
    {
        scannerJob(stateLocation.resolve("no-such-scanner").toString()).run(new NullProgressMonitor());

        assertEquals(Messages.Analysis_ScannerNotFound, lastStatus());
    }

    /**
     * The catch-all of {@link AnalysisJob#run}: an unexpected runtime failure must reach the status line as
     * a scrubbed message rather than escaping as a job error, and must never carry a credential with it.
     */
    @Test
    public void anUnexpectedRuntimeFailureIsReportedScrubbedInsteadOfEscaping()
    {
        client.languagesRuntimeFailure = new IllegalStateException("boom, token=squ_leaked");

        IStatus result = scannerJob("").run(new NullProgressMonitor());

        assertSame(Status.OK_STATUS, result);
        assertFalse(lastStatus(), lastStatus().contains("squ_leaked"));
        assertTrue(lastStatus(), lastStatus().startsWith(NLS.bind(Messages.IssuesView_Status_Error, "")));
    }

    // --- report-task.txt handover and Compute Engine polling -----------------------------------------

    private Path workDirWithTask(String content) throws IOException
    {
        Path workDir = Files.createDirectories(stateLocation.resolve("scannerwork"));
        Files.writeString(workDir.resolve("report-task.txt"), content, StandardCharsets.UTF_8);
        return workDir;
    }

    @Test
    public void aRunWithoutAReportTaskFileFinishesRightAwayAndRefreshesTheView() throws IOException
    {
        Path workDir = Files.createDirectories(stateLocation.resolve("empty-work"));

        IStatus result = pollingJob().awaitServerProcessing(workDir, new NullProgressMonitor());

        assertSame(Status.OK_STATUS, result);
        assertEquals(Messages.Analysis_Done, lastStatus());
        assertEquals(1, successes.get());
        assertTrue("no task id means nothing to poll", client.polledTaskIds.isEmpty());
    }

    @Test
    public void pollsTheTaskFromTheReportUntilItSucceedsAndOnlyThenRefreshesTheView() throws IOException
    {
        Path workDir = workDirWithTask("projectKey=proj\nceTaskId=" + TASK_ID + "\n");
        client.tasks.add(new CeTask("PENDING", ""));
        client.tasks.add(new CeTask("IN_PROGRESS", ""));
        client.tasks.add(new CeTask("SUCCESS", ""));

        IStatus result = pollingJob().awaitServerProcessing(workDir, new NullProgressMonitor());

        assertSame(Status.OK_STATUS, result);
        assertEquals(List.of(TASK_ID, TASK_ID, TASK_ID), client.polledTaskIds);
        assertEquals(Messages.Analysis_ServerProcessing, statuses.get(0));
        assertEquals(Messages.Analysis_Done, lastStatus());
        assertEquals(1, successes.get());
    }

    @Test
    public void aFailedTaskReportsItsErrorMessageAndDoesNotRefreshTheView() throws IOException
    {
        Path workDir = workDirWithTask("ceTaskId=" + TASK_ID + "\n");
        client.tasks.add(new CeTask("FAILED", "Not enough memory on the server"));

        IStatus result = pollingJob().awaitServerProcessing(workDir, new NullProgressMonitor());

        assertSame(Status.OK_STATUS, result);
        assertEquals("Not enough memory on the server", lastStatus());
        assertEquals(0, successes.get());
    }

    @Test
    public void aFailedTaskWithoutAMessageReportsItsStatus() throws IOException
    {
        Path workDir = workDirWithTask("ceTaskId=" + TASK_ID + "\n");
        client.tasks.add(new CeTask("CANCELED", ""));

        pollingJob().awaitServerProcessing(workDir, new NullProgressMonitor());

        assertEquals("CANCELED", lastStatus());
        assertEquals(0, successes.get());
    }

    /**
     * Exhausting the budget must be said out loud: the status line otherwise keeps the "server is
     * processing the report" text for the rest of the session.
     */
    @Test
    public void anExhaustedPollingBudgetIsReportedAsATimeout() throws IOException
    {
        Path workDir = workDirWithTask("ceTaskId=" + TASK_ID + "\n");
        AnalysisRequest request = new AnalysisRequest(project, binding(),
            SonarConnection.of("https://sonar.example.com", "squ_token", 42),
            new AnalysisLaunchConfig(AnalysisLaunchMode.LOCAL_AUTO, "", "", ""), "main", true, "", stateLocation,
            client);
        // A budget of 60 ms exercises the same code path as the production ten minutes, without spending them;
        // the reported figure is derived from the budget, so it reads as "within 0 min" here.
        AnalysisJob shortBudget = new AnalysisJob(request, successes::incrementAndGet, statuses::add,
            timeout -> trigger, new AnalysisJob.PollingBudget(60L, 5L));

        IStatus result = shortBudget.awaitServerProcessing(workDir, new NullProgressMonitor());

        assertSame(Status.OK_STATUS, result);
        assertEquals(NLS.bind(Messages.Analysis_ServerTimeout, Long.valueOf(0L)), lastStatus());
        assertEquals(0, successes.get());
        assertTrue("the budget must have been spent polling", client.polledTaskIds.size() > 1);
    }

    @Test
    public void aServerFailureWhilePollingIsReportedAndStopsThePolling() throws IOException
    {
        Path workDir = workDirWithTask("ceTaskId=" + TASK_ID + "\n");
        client.ceFailure = new SonarServerException(503, "gateway timeout");

        IStatus result = pollingJob().awaitServerProcessing(workDir, new NullProgressMonitor());

        assertSame(Status.OK_STATUS, result);
        assertEquals("gateway timeout", lastStatus());
        assertEquals(1, client.polledTaskIds.size());
        assertEquals(0, successes.get());
    }

    @Test
    public void aCancelledMonitorStopsThePollingWithoutRefreshingTheView() throws IOException
    {
        Path workDir = workDirWithTask("ceTaskId=" + TASK_ID + "\n");
        IProgressMonitor monitor = new NullProgressMonitor();
        monitor.setCanceled(true);

        IStatus result = pollingJob().awaitServerProcessing(workDir, monitor);

        assertSame(Status.CANCEL_STATUS, result);
        assertTrue(client.polledTaskIds.isEmpty());
        assertEquals(0, successes.get());
    }

    // --- scanner base directory and sonar.sources ----------------------------------------------------

    @Test
    public void withoutAPathPrefixTheProjectLocationIsTheBaseDirectory()
    {
        AnalysisJob.SourceRoot root = scannerJob("").resolveSourceRoot();

        assertEquals(project.getLocation().toFile().toPath(), root.baseDir());
        assertEquals("src", root.sources());
    }

    @Test
    public void withAPathPrefixTheRepositoryRootIsTheBaseDirectoryAndTheSourcesArePrefixed() throws IOException
    {
        Path projectDir = project.getLocation().toFile().toPath();
        Files.createDirectories(projectDir.resolve(".git"));
        AnalysisJob prefixed = job(new AnalysisLaunchConfig(AnalysisLaunchMode.LOCAL_PATH, "", "", ""),
            new ProjectBinding("proj", "", "conf/"), "main", "");

        AnalysisJob.SourceRoot root = prefixed.resolveSourceRoot();

        assertEquals(projectDir, root.baseDir());
        assertEquals("conf/src", root.sources());
    }

    @Test
    public void theRepositoryRootIsFoundByWalkingUp() throws IOException
    {
        Path repo = Files.createDirectories(stateLocation.resolve("repo"));
        Files.createDirectories(repo.resolve(".git"));
        Path deep = Files.createDirectories(repo.resolve("a/b/c"));

        assertEquals(repo, AnalysisJob.findRepositoryRoot(deep.toFile()));
    }

    /** A worktree file, not a directory, is a repository root too (linked worktrees, submodules). */
    @Test
    public void aGitFileCountsAsARepositoryRoot() throws IOException
    {
        Path repo = Files.createDirectories(stateLocation.resolve("worktree"));
        Files.writeString(repo.resolve(".git"), "gitdir: ../real/.git", StandardCharsets.UTF_8);

        assertEquals(repo, AnalysisJob.findRepositoryRoot(repo.resolve("src").toFile()));
    }

    @Test
    public void theUpwardWalkGivesUpInsteadOfClimbingToTheFilesystemRoot() throws IOException
    {
        Path repo = Files.createDirectories(stateLocation.resolve("far"));
        Files.createDirectories(repo.resolve(".git"));
        Path deep = Files.createDirectories(repo.resolve("1/2/3/4/5/6/7/8/9/10/11/12"));

        assertNull(AnalysisJob.findRepositoryRoot(deep.toFile()));
        assertNotNull(AnalysisJob.findRepositoryRoot(repo.resolve("1/2/3").toFile()));
    }

    private Path existingFile()
    {
        try
        {
            return Files.createFile(stateLocation.resolve("scanner-" + System.nanoTime()));
        }
        catch (IOException e)
        {
            throw new IllegalStateException(e);
        }
    }
}
