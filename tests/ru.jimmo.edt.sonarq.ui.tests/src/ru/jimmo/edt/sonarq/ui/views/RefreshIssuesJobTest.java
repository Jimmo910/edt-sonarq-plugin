/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.ui.views;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.osgi.util.NLS;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import ru.jimmo.edt.sonarq.core.client.SonarServerException;
import ru.jimmo.edt.sonarq.core.model.BranchInfo;
import ru.jimmo.edt.sonarq.core.model.IssueQuery;
import ru.jimmo.edt.sonarq.core.model.IssueSnapshot;
import ru.jimmo.edt.sonarq.core.model.SonarRule;
import ru.jimmo.edt.sonarq.core.provider.IIssueProvider;
import ru.jimmo.edt.sonarq.core.settings.ProjectBinding;
import ru.jimmo.edt.sonarq.ui.Messages;

/** Tests for {@link RefreshIssuesJob}. */
public class RefreshIssuesJobTest
{
    private static final class FakeProvider implements IIssueProvider
    {
        List<BranchInfo> branches = List.of();
        SonarServerException failure;
        IssueQuery lastQuery;

        @Override
        public IssueSnapshot fetchIssues(IssueQuery query, IProgressMonitor monitor) throws SonarServerException
        {
            if (failure != null)
            {
                throw failure;
            }
            lastQuery = query;
            return new IssueSnapshot(query, List.of(), 0, Instant.now());
        }

        @Override
        public SonarRule describeRule(String ruleKey)
        {
            return new SonarRule(ruleKey, "", "");
        }

        @Override
        public List<BranchInfo> listBranches(String projectKey)
        {
            return branches;
        }
    }

    private IProject project;

    @Before
    public void setUp() throws CoreException
    {
        project = ResourcesPlugin.getWorkspace().getRoot().getProject("refresh-test");
        if (!project.exists())
        {
            project.create(new NullProgressMonitor());
        }
        project.open(new NullProgressMonitor());
    }

    @After
    public void tearDown() throws CoreException
    {
        project.delete(true, true, new NullProgressMonitor());
    }

    private RefreshResult run(FakeProvider provider, ProjectBinding binding, String sessionBranch)
        throws InterruptedException
    {
        AtomicReference<RefreshResult> result = new AtomicReference<>();
        RefreshIssuesJob job = new RefreshIssuesJob(provider, project, binding, sessionBranch, result::set);
        job.schedule();
        job.join();
        return result.get();
    }

    @Test
    public void queriesMainBranchWhenLocalBranchUnknown() throws Exception
    {
        FakeProvider provider = new FakeProvider();
        provider.branches = List.of(new BranchInfo("main", true));
        RefreshResult result = run(provider, new ProjectBinding("p", "", ""), null);
        assertFalse(result.isError());
        assertEquals("main", result.branchState().effectiveBranch());
        assertEquals("main", provider.lastQuery.branch());
    }

    @Test
    public void branchOverrideFromBindingIsUsed() throws Exception
    {
        FakeProvider provider = new FakeProvider();
        provider.branches = List.of(new BranchInfo("main", true), new BranchInfo("release/1", false));
        RefreshResult result = run(provider, new ProjectBinding("p", "release/1", ""), null);
        assertEquals("release/1", result.branchState().effectiveBranch());
        assertFalse(result.branchState().missingOnServer());
    }

    @Test
    public void sessionBranchWinsOverBinding() throws Exception
    {
        FakeProvider provider = new FakeProvider();
        provider.branches = List.of(new BranchInfo("main", true), new BranchInfo("release/1", false));
        RefreshResult result = run(provider, new ProjectBinding("p", "release/1", ""), "main");
        assertEquals("main", result.branchState().effectiveBranch());
    }

    @Test
    public void serverErrorIsReportedNotThrown() throws Exception
    {
        FakeProvider provider = new FakeProvider();
        provider.failure = new SonarServerException(500, "boom");
        RefreshResult result = run(provider, new ProjectBinding("p", "", ""), null);
        assertTrue(result.isError());
        assertEquals("boom", result.errorMessage());
    }

    @Test
    public void communityServerQueriesWithoutBranch() throws Exception
    {
        FakeProvider provider = new FakeProvider();
        provider.branches = List.of();
        RefreshResult result = run(provider, new ProjectBinding("p", "", ""), null);
        assertFalse(result.isError());
        assertNull(provider.lastQuery.branch());
        assertFalse(result.branchState().branchesSupported());
    }

    @Test
    public void authFailureProducesTokenHint() throws Exception
    {
        FakeProvider provider = new FakeProvider();
        provider.failure = new SonarServerException(401, "HTTP 401");
        RefreshResult result = run(provider, new ProjectBinding("p", "", ""), null);
        assertTrue(result.isError());
        assertEquals(NLS.bind(Messages.IssuesView_Status_AuthError, "401"), result.errorMessage());
    }
}
