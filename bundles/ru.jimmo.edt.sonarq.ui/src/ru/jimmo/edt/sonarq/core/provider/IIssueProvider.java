/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.core.provider;

import java.util.List;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.OperationCanceledException;

import ru.jimmo.edt.sonarq.core.client.SonarServerException;
import ru.jimmo.edt.sonarq.core.model.BranchInfo;
import ru.jimmo.edt.sonarq.core.model.IssueQuery;
import ru.jimmo.edt.sonarq.core.model.IssueSnapshot;
import ru.jimmo.edt.sonarq.core.model.SonarRule;

/** Source of SonarQube issues, rule descriptions and branch information for one project. */
public interface IIssueProvider
{
    /**
     * Fetches all unresolved issues matching the query, page by page, up to the 10 000 issue cap.
     *
     * @param query the search scope, not {@code null}
     * @param monitor the progress monitor to report progress to and check for cancellation, not {@code null}
     * @return the loaded snapshot, never {@code null}
     * @throws SonarServerException if a page request fails
     * @throws OperationCanceledException if the monitor is cancelled before the fetch completes
     */
    IssueSnapshot fetchIssues(IssueQuery query, IProgressMonitor monitor) throws SonarServerException;

    /**
     * Describes a rule, using a per-instance cache so repeated calls for the same rule do not
     * re-query the server.
     *
     * @param ruleKey the rule key, e.g. {@code bsl:MethodSize}, not {@code null}
     * @return the rule description, never {@code null}
     * @throws SonarServerException if the call fails
     */
    SonarRule describeRule(String ruleKey) throws SonarServerException;

    /**
     * Lists the branches known to the server for a project.
     *
     * @param projectKey the SonarQube project key, not {@code null}
     * @return the branches, never {@code null}; empty when the server edition has no branch support
     * @throws SonarServerException if the call fails
     */
    List<BranchInfo> listBranches(String projectKey) throws SonarServerException;
}
