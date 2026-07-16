/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.core.client;

import java.util.List;

import ru.jimmo.edt.sonarq.core.model.BranchInfo;
import ru.jimmo.edt.sonarq.core.model.ComponentInfo;
import ru.jimmo.edt.sonarq.core.model.IssueQuery;
import ru.jimmo.edt.sonarq.core.model.IssuesPage;
import ru.jimmo.edt.sonarq.core.model.SonarRule;

/** Client for the SonarQube Web API operations used by this plug-in. */
public interface ISonarServerClient
{
    /**
     * Returns the SonarQube server version.
     *
     * @return the server version string, never {@code null}
     * @throws SonarServerException if the call fails
     */
    String serverVersion() throws SonarServerException;

    /**
     * Lists the branches of a project.
     *
     * @param projectKey the SonarQube project key, not {@code null}
     * @return the branches, never {@code null}; an empty list if the server responds with 400 or 404
     * @throws SonarServerException if the call fails for any other reason
     */
    List<BranchInfo> listBranches(String projectKey) throws SonarServerException;

    /**
     * Searches issues for the given query, one page at a time.
     *
     * @param query the search scope, not {@code null}
     * @param page the 1-based page number to fetch
     * @return the requested page, never {@code null}
     * @throws SonarServerException if the call fails
     */
    IssuesPage searchIssuesPage(IssueQuery query, int page) throws SonarServerException;

    /**
     * Fetches the description of a rule.
     *
     * @param ruleKey the rule key, e.g. {@code bsl:MethodSize}, not {@code null}
     * @return the rule description, never {@code null}
     * @throws SonarServerException if the call fails
     */
    SonarRule showRule(String ruleKey) throws SonarServerException;

    /**
     * Searches projects by (partial) name.
     *
     * @param namePart the name fragment to search for, not {@code null}
     * @return the matching projects, never {@code null}
     * @throws SonarServerException if the call fails
     */
    List<ComponentInfo> searchProjects(String namePart) throws SonarServerException;
}
