/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.core.analysis;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Triggers an external CI pipeline by sending an empty-body POST request to a webhook URL.
 *
 * <p>The target URL is built from a template containing zero or more {@code {branch}} placeholders,
 * for example the GitLab pipeline trigger endpoint:
 * {@code https://gitlab.example.com/api/v4/projects/123/trigger/pipeline?token=SECRET&ref={branch}}.
 *
 * <p>Instances are safe for concurrent use from multiple threads.
 */
public final class CiTriggerClient
{
    private static final String BRANCH_PLACEHOLDER = "{branch}"; //$NON-NLS-1$
    private static final String AUTHORIZATION_HEADER = "Authorization"; //$NON-NLS-1$

    private final int timeoutSeconds;
    private final HttpClient http;

    /**
     * Creates a client with the given connect and request timeout.
     *
     * @param timeoutSeconds the connect and request timeout, in seconds
     */
    public CiTriggerClient(int timeoutSeconds)
    {
        this.timeoutSeconds = timeoutSeconds;
        this.http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(timeoutSeconds))
            .build();
    }

    /**
     * Triggers the CI pipeline by POSTing an empty body to the URL built from {@code urlTemplate}.
     *
     * <p>Every occurrence of the literal {@code {branch}} placeholder in {@code urlTemplate} is replaced
     * with the URL-encoded branch name. For example, given the GitLab trigger URL template
     * {@code https://gitlab.example.com/api/v4/projects/123/trigger/pipeline?token=SECRET&ref={branch}}
     * and branch {@code feature/x}, the request is POSTed to
     * {@code https://gitlab.example.com/api/v4/projects/123/trigger/pipeline?token=SECRET&ref=feature%2Fx}.
     *
     * @param urlTemplate the URL template containing zero or more {@code {branch}} placeholders, not {@code null}
     * @param branch the branch name to substitute, not {@code null}
     * @param secretHeader the verbatim value of the {@code Authorization} header (e.g. {@code "Bearer <token>"}
     *     or a custom scheme), or {@code null}/blank to send no {@code Authorization} header
     * @return the HTTP status code returned by the CI server
     * @throws IOException if the request fails
     * @throws InterruptedException if the current thread is interrupted while waiting for the response
     */
    public int trigger(String urlTemplate, String branch, String secretHeader)
        throws IOException, InterruptedException
    {
        String url = urlTemplate.replace(BRANCH_PLACEHOLDER, URLEncoder.encode(branch, StandardCharsets.UTF_8));
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(timeoutSeconds))
            .POST(HttpRequest.BodyPublishers.noBody());
        if (secretHeader != null && !secretHeader.isBlank())
        {
            requestBuilder.header(AUTHORIZATION_HEADER, secretHeader);
        }
        HttpResponse<Void> response = http.send(requestBuilder.build(), HttpResponse.BodyHandlers.discarding());
        return response.statusCode();
    }
}
