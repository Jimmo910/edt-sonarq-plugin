/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.core.provider;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.OperationCanceledException;

import ru.jimmo.edt.sonarq.core.client.ISonarServerClient;
import ru.jimmo.edt.sonarq.core.client.SonarServerException;
import ru.jimmo.edt.sonarq.core.model.BranchInfo;
import ru.jimmo.edt.sonarq.core.model.IssueQuery;
import ru.jimmo.edt.sonarq.core.model.IssueSnapshot;
import ru.jimmo.edt.sonarq.core.model.IssuesPage;
import ru.jimmo.edt.sonarq.core.model.SonarIssue;
import ru.jimmo.edt.sonarq.core.model.SonarRule;

/** Fetches issues from a SonarQube server, page by page, with a session-scoped rule cache. */
public final class ServerIssueProvider implements IIssueProvider
{
    /** SonarQube caps paged issue search results at this count. */
    public static final int MAX_ISSUES = 10_000;

    private final ISonarServerClient client;
    private final Map<String, SonarRule> ruleCache = new ConcurrentHashMap<>();
    private volatile Boolean branchAnalysisSupported;

    /**
     * Creates a provider on top of the given client.
     *
     * @param client the server client, not {@code null}
     */
    public ServerIssueProvider(ISonarServerClient client)
    {
        this.client = client;
    }

    @Override
    public IssueSnapshot fetchIssues(IssueQuery query, IProgressMonitor monitor) throws SonarServerException
    {
        List<SonarIssue> collected = new ArrayList<>();
        int total = 0;
        for (int page = 1;; page++)
        {
            if (monitor.isCanceled())
            {
                throw new OperationCanceledException();
            }
            IssuesPage result = client.searchIssuesPage(query, page);
            total = result.total();
            if (result.issues().isEmpty())
            {
                break;
            }
            collected.addAll(result.issues());
            if (collected.size() >= Math.min(total, MAX_ISSUES))
            {
                break;
            }
        }
        return new IssueSnapshot(query, List.copyOf(collected), total, Instant.now());
    }

    @Override
    public SonarRule describeRule(String ruleKey) throws SonarServerException
    {
        SonarRule cached = ruleCache.get(ruleKey);
        if (cached != null)
        {
            return cached;
        }
        SonarRule rule = client.showRule(ruleKey);
        ruleCache.put(ruleKey, rule);
        return rule;
    }

    @Override
    public List<BranchInfo> listBranches(String projectKey) throws SonarServerException
    {
        return client.listBranches(projectKey);
    }

    @Override
    public boolean branchAnalysisSupported() throws SonarServerException
    {
        Boolean cached = branchAnalysisSupported;
        if (cached != null)
        {
            return cached.booleanValue();
        }
        String edition = client.serverEdition();
        boolean supported = "developer".equals(edition) //$NON-NLS-1$
            || "enterprise".equals(edition) //$NON-NLS-1$
            || "datacenter".equals(edition); //$NON-NLS-1$
        branchAnalysisSupported = Boolean.valueOf(supported);
        return supported;
    }
}
