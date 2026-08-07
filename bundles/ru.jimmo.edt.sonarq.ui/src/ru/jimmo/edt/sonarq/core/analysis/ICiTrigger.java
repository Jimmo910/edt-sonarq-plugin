/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.core.analysis;

import java.io.IOException;

/**
 * Starts an external CI pipeline run.
 *
 * <p>An implementation owns the transport it speaks over, so a caller has to release it through
 * {@link #close()} once the trigger is done - see {@link CiTriggerClient}, whose JDK
 * {@link java.net.http.HttpClient} owns a selector thread and a connection pool and must not be left to the
 * garbage collector (review minor M2). The interface also gives the dispatching job a seam, so the trigger
 * path can be driven headlessly without a CI server.
 */
public interface ICiTrigger extends AutoCloseable
{
    /**
     * Triggers the CI pipeline described by the URL template.
     *
     * @param urlTemplate the URL template containing zero or more {@code {branch}} placeholders, not
     *     {@code null}
     * @param branch the branch name to substitute, not {@code null}
     * @param secretHeader the verbatim {@code Authorization} header value, or {@code null}/blank to send none
     * @return the HTTP status code returned by the CI server
     * @throws IOException if the request fails
     * @throws InterruptedException if the calling thread is interrupted while waiting for the response
     */
    int trigger(String urlTemplate, String branch, String secretHeader) throws IOException, InterruptedException;

    /**
     * Releases the transport. Declares no checked exception, so it never turns a try-with-resources tail into
     * a second failure hiding the one the caller is reporting.
     */
    @Override
    void close();
}
