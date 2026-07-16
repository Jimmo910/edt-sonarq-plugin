/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.core.client;

/**
 * SonarQube server connection settings.
 *
 * @param baseUrl the server URL without a trailing slash, not {@code null}
 * @param token the user token, not {@code null}, may be empty
 * @param timeoutSeconds the request timeout in seconds, positive
 */
public record SonarConnection(String baseUrl, String token, int timeoutSeconds)
{
    /**
     * Creates a connection normalizing the base URL.
     *
     * @param baseUrl the server URL, not {@code null}
     * @param token the user token, not {@code null}
     * @param timeoutSeconds the request timeout in seconds, positive
     * @return the connection, never {@code null}
     */
    public static SonarConnection of(String baseUrl, String token, int timeoutSeconds)
    {
        String normalized = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl; //$NON-NLS-1$
        return new SonarConnection(normalized, token, timeoutSeconds);
    }
}
