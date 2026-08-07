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
 *
 * <p>An instance owns a JDK {@link HttpClient}, which owns a selector thread and a connection pool, so it
 * must be released through {@link #close()} rather than dropped on the floor for the garbage collector to
 * find - one CI trigger click used to leak one selector thread (review minor M2). The client is built per
 * trigger rather than shared, unlike the server client of {@code SonarHttpClients}: a trigger is a single
 * one-shot request against a URL the user may edit between clicks, so there is nothing to keep alive
 * afterwards.
 */
public final class CiTriggerClient implements ICiTrigger
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
    @Override
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

    /**
     * Releases the underlying {@link HttpClient} and its selector thread.
     *
     * <p>Uses {@link HttpClient#shutdownNow()} rather than {@link HttpClient#close()}, exactly as
     * {@code SonarHttpClient#close} does: {@code close()} blocks until every in-flight exchange finishes,
     * which would let a hung CI server stall the analysis job that is disposing the client. Idempotent; the
     * instance must not be used afterwards.
     */
    @Override
    public void close()
    {
        http.shutdownNow();
    }
}
