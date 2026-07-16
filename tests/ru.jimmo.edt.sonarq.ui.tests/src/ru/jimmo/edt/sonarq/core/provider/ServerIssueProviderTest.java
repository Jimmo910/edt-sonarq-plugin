/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.core.provider;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.OperationCanceledException;
import org.junit.Test;

import ru.jimmo.edt.sonarq.core.client.ISonarServerClient;
import ru.jimmo.edt.sonarq.core.model.BranchInfo;
import ru.jimmo.edt.sonarq.core.model.ComponentInfo;
import ru.jimmo.edt.sonarq.core.model.IssueQuery;
import ru.jimmo.edt.sonarq.core.model.IssueSnapshot;
import ru.jimmo.edt.sonarq.core.model.IssuesPage;
import ru.jimmo.edt.sonarq.core.model.SonarIssue;
import ru.jimmo.edt.sonarq.core.model.SonarIssueType;
import ru.jimmo.edt.sonarq.core.model.SonarRule;
import ru.jimmo.edt.sonarq.core.model.SonarSeverity;

/** Tests for {@link ServerIssueProvider}. */
public class ServerIssueProviderTest
{
    /** Serves pre-canned pages and counts calls. */
    private static final class FakeClient implements ISonarServerClient
    {
        final List<IssuesPage> pages = new ArrayList<>();
        int searchCalls;
        int ruleCalls;

        @Override
        public IssuesPage searchIssuesPage(IssueQuery query, int page)
        {
            searchCalls++;
            return pages.get(Math.min(page, pages.size()) - 1);
        }

        @Override
        public SonarRule showRule(String ruleKey)
        {
            ruleCalls++;
            return new SonarRule(ruleKey, "name", "<p/>");
        }

        @Override
        public String serverVersion()
        {
            return "10.0";
        }

        @Override
        public List<BranchInfo> listBranches(String projectKey)
        {
            return List.of();
        }

        @Override
        public List<ComponentInfo> searchProjects(String namePart)
        {
            return List.of();
        }
    }

    /** Always serves a full 500-issue page with unique keys, so only the cap can stop the loop. */
    private static final class EndlessFakeClient implements ISonarServerClient
    {
        int searchCalls;

        @Override
        public IssuesPage searchIssuesPage(IssueQuery query, int page)
        {
            searchCalls++;
            return ServerIssueProviderTest.page(12_000, 500, searchCalls + "-");
        }

        @Override
        public SonarRule showRule(String ruleKey)
        {
            return new SonarRule(ruleKey, "name", "<p/>");
        }

        @Override
        public String serverVersion()
        {
            return "10.0";
        }

        @Override
        public List<BranchInfo> listBranches(String projectKey)
        {
            return List.of();
        }

        @Override
        public List<ComponentInfo> searchProjects(String namePart)
        {
            return List.of();
        }
    }

    private static SonarIssue issue(String key)
    {
        return new SonarIssue(key, "bsl:R", SonarSeverity.MAJOR, SonarIssueType.CODE_SMELL,
            "p:src/M.bsl", "m", 1);
    }

    private static IssuesPage page(int total, int count, String prefix)
    {
        List<SonarIssue> issues = new ArrayList<>();
        for (int i = 0; i < count; i++)
        {
            issues.add(issue(prefix + i));
        }
        return new IssuesPage(issues, total, 1, 500);
    }

    @Test
    public void collectsAllPagesUpToTotal() throws Exception
    {
        FakeClient client = new FakeClient();
        client.pages.add(page(1200, 500, "a"));
        client.pages.add(page(1200, 500, "b"));
        client.pages.add(page(1200, 200, "c"));
        IssueSnapshot snapshot = new ServerIssueProvider(client)
            .fetchIssues(new IssueQuery("p", null), new NullProgressMonitor());
        assertEquals(3, client.searchCalls);
        assertEquals(1200, snapshot.issues().size());
        assertEquals(1200, snapshot.serverTotal());
        assertFalse(snapshot.truncated());
    }

    @Test
    public void emptyPageStopsTheLoop() throws Exception
    {
        FakeClient client = new FakeClient();
        client.pages.add(page(1000, 500, "a"));
        client.pages.add(new IssuesPage(List.of(), 1000, 2, 500));
        IssueSnapshot snapshot = new ServerIssueProvider(client)
            .fetchIssues(new IssueQuery("p", null), new NullProgressMonitor());
        assertEquals(500, snapshot.issues().size());
        assertTrue(snapshot.truncated());
    }

    @Test(expected = OperationCanceledException.class)
    public void cancelledMonitorAborts() throws Exception
    {
        FakeClient client = new FakeClient();
        client.pages.add(page(10, 10, "a"));
        NullProgressMonitor cancelled = new NullProgressMonitor();
        cancelled.setCanceled(true);
        new ServerIssueProvider(client).fetchIssues(new IssueQuery("p", null), cancelled);
    }

    @Test
    public void capsCollectionAtMaxIssues() throws Exception
    {
        EndlessFakeClient client = new EndlessFakeClient();
        IssueSnapshot snapshot = new ServerIssueProvider(client)
            .fetchIssues(new IssueQuery("p", null), new NullProgressMonitor());
        assertEquals(ServerIssueProvider.MAX_ISSUES, snapshot.issues().size());
        assertEquals(12_000, snapshot.serverTotal());
        assertTrue(snapshot.truncated());
        assertEquals(20, client.searchCalls);
    }

    @Test
    public void ruleDescriptionsAreCached() throws Exception
    {
        FakeClient client = new FakeClient();
        ServerIssueProvider provider = new ServerIssueProvider(client);
        provider.describeRule("bsl:R");
        provider.describeRule("bsl:R");
        assertEquals(1, client.ruleCalls);
    }
}
