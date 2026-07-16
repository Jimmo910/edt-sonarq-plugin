/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.core.client;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Set;

import ru.jimmo.edt.sonarq.core.model.BranchInfo;
import ru.jimmo.edt.sonarq.core.model.CeTask;
import ru.jimmo.edt.sonarq.core.model.ComponentInfo;
import ru.jimmo.edt.sonarq.core.model.IssueQuery;
import ru.jimmo.edt.sonarq.core.model.IssuesPage;
import ru.jimmo.edt.sonarq.core.model.SonarRule;

/**
 * {@link ISonarServerClient} implementation on top of {@link java.net.http.HttpClient}.
 * Instances are safe for concurrent use from multiple threads.
 */
public final class SonarHttpClient implements ISonarServerClient
{
    private static final int PAGE_SIZE = 500;

    private final SonarConnection connection;
    private final HttpClient http;
    private volatile boolean useBasicAuth;

    /**
     * Creates a client for the given connection.
     *
     * @param connection the connection settings, not {@code null}
     */
    public SonarHttpClient(SonarConnection connection)
    {
        this.connection = connection;
        this.http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(connection.timeoutSeconds()))
            .build();
    }

    @Override
    public String serverVersion() throws SonarServerException
    {
        return get("/api/server/version"); //$NON-NLS-1$
    }

    @Override
    public List<BranchInfo> listBranches(String projectKey) throws SonarServerException
    {
        try
        {
            return SonarJsonParser.parseBranches(
                get("/api/project_branches/list?project=" + encode(projectKey))); //$NON-NLS-1$
        }
        catch (SonarServerException e)
        {
            if (e.getStatusCode() == 400 || e.getStatusCode() == 404)
            {
                return List.of();
            }
            throw e;
        }
    }

    @Override
    public IssuesPage searchIssuesPage(IssueQuery query, int page) throws SonarServerException
    {
        StringBuilder url = new StringBuilder("/api/issues/search?resolved=false&ps=") //$NON-NLS-1$
            .append(PAGE_SIZE)
            .append("&p=").append(page) //$NON-NLS-1$
            .append("&componentKeys=").append(encode(query.projectKey())); //$NON-NLS-1$
        if (query.branch() != null && !query.branch().isEmpty())
        {
            url.append("&branch=").append(encode(query.branch())); //$NON-NLS-1$
        }
        return SonarJsonParser.parseIssuesPage(get(url.toString()));
    }

    @Override
    public SonarRule showRule(String ruleKey) throws SonarServerException
    {
        return SonarJsonParser.parseRule(get("/api/rules/show?key=" + encode(ruleKey))); //$NON-NLS-1$
    }

    @Override
    public List<ComponentInfo> searchProjects(String namePart) throws SonarServerException
    {
        return SonarJsonParser.parseComponents(
            get("/api/components/search?qualifiers=TRK&q=" + encode(namePart))); //$NON-NLS-1$
    }

    @Override
    public Set<String> serverLanguages() throws SonarServerException
    {
        return SonarJsonParser.parseLanguages(get("/api/languages/list")); //$NON-NLS-1$
    }

    @Override
    public CeTask ceTaskStatus(String taskId) throws SonarServerException
    {
        return SonarJsonParser.parseCeTask(get("/api/ce/task?id=" + encode(taskId))); //$NON-NLS-1$
    }

    private String get(String pathAndQuery) throws SonarServerException
    {
        boolean usedBasic = useBasicAuth;
        HttpResponse<String> response = send(pathAndQuery, usedBasic);
        if (response.statusCode() == 401 && !usedBasic)
        {
            useBasicAuth = true;
            response = send(pathAndQuery, true);
        }
        if (response.statusCode() >= 400)
        {
            throw new SonarServerException(response.statusCode(),
                "HTTP " + response.statusCode() + " for " + pathAndQuery); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return response.body();
    }

    private HttpResponse<String> send(String pathAndQuery, boolean basicAuth) throws SonarServerException
    {
        HttpRequest request = HttpRequest.newBuilder(URI.create(connection.baseUrl() + pathAndQuery))
            .timeout(Duration.ofSeconds(connection.timeoutSeconds()))
            .header("Authorization", authHeader(basicAuth)) //$NON-NLS-1$
            .GET()
            .build();
        try
        {
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        }
        catch (IOException e)
        {
            throw new SonarServerException(e.getMessage(), e);
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            throw new SonarServerException(e.getMessage(), e);
        }
    }

    private String authHeader(boolean basicAuth)
    {
        if (basicAuth)
        {
            String credentials = connection.token() + ":"; //$NON-NLS-1$
            return "Basic " //$NON-NLS-1$
                + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
        }
        return "Bearer " + connection.token(); //$NON-NLS-1$
    }

    private static String encode(String value)
    {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
